/*
 * AudioArch Native C Receiver Daemon
 * High-performance, ultra-low-latency ALSA audio player for ARM Android.
 * Zero JVM, zero Dalvik, minimal CPU and RAM footprint.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <tinyalsa/asoundlib.h>

static volatile int running = 1;

static void sig_handler(int sig) {
    (void)sig;
    running = 0;
}

static void configure_mixer(int card) {
    struct mixer *mixer = mixer_open(card);
    if (!mixer) {
        printf("[Daemon] Warning: Could not open mixer for card %d\n", card);
        return;
    }

    struct mixer_ctl *ctl;
    // Turn on Speaker and Headphone
    ctl = mixer_get_ctl_by_name(mixer, "Speaker Function");
    if (ctl) mixer_ctl_set_value(ctl, 0, 1);

    ctl = mixer_get_ctl_by_name(mixer, "HeadPhone Function");
    if (ctl) mixer_ctl_set_value(ctl, 0, 1);

    // Set volumes to max
    ctl = mixer_get_ctl_by_name(mixer, "Master Playback Volume");
    if (ctl) {
        mixer_ctl_set_value(ctl, 0, 15);
        mixer_ctl_set_value(ctl, 1, 15);
    }

    ctl = mixer_get_ctl_by_name(mixer, "HeadPhone Playback Volume");
    if (ctl) {
        mixer_ctl_set_value(ctl, 0, 15);
        mixer_ctl_set_value(ctl, 1, 15);
    }

    mixer_close(mixer);
    printf("[Daemon] Mixer configured: Speaker=On, Headphone=On, Volume=15/15\n");
}

int main(int argc, char *argv[]) {
    int port = 12345;
    int rate = 44100;
    int card = 0;
    int device = 0;

    if (argc > 1) port = atoi(argv[1]);
    if (argc > 2) rate = atoi(argv[2]);
    if (argc > 3) card = atoi(argv[3]);
    if (argc > 4) device = atoi(argv[4]);

    signal(SIGINT, sig_handler);
    signal(SIGTERM, sig_handler);
    signal(SIGPIPE, SIG_IGN);
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    printf("[Daemon] Starting Native C AudioArch Daemon on port %d (rate=%dHz, card=%d, device=%d)\n",
           port, rate, card, device);

    configure_mixer(card);

    // Setup TCP server
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("[Daemon] socket");
        return 1;
    }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(port);

    if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("[Daemon] bind");
        close(server_fd);
        return 1;
    }

    if (listen(server_fd, 2) < 0) {
        perror("[Daemon] listen");
        close(server_fd);
        return 1;
    }

    printf("[Daemon] Listening on port %d. Waiting for host audio...\n", port);

    // Configure ALSA hardware buffer: 512 frames period, 2 periods = 1024 frames (~23ms total latency!)
    struct pcm_config config;
    memset(&config, 0, sizeof(config));
    config.channels = 2;
    config.rate = rate;
    config.period_size = 512;
    config.period_count = 2;
    config.format = PCM_FORMAT_S16_LE;
    config.start_threshold = 512;
    config.stop_threshold = 1024;

    const int chunk_bytes = 512 * 4; // 512 frames * 2 channels * 2 bytes = 2048 bytes
    char *buffer = malloc(chunk_bytes);
    if (!buffer) {
        fprintf(stderr, "[Daemon] Out of memory\n");
        close(server_fd);
        return 1;
    }

    while (running) {
        printf("[Daemon] Ready for next connection.\n");
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server_fd, (struct sockaddr *)&client_addr, &client_len);
        if (client_fd < 0) {
            if (!running) break;
            continue;
        }

        // Optimize TCP socket for lowest latency
        int nodelay = 1;
        setsockopt(client_fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));
        int rcvbuf = 4096;
        setsockopt(client_fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

        printf("[Daemon] Host connected! Opening ALSA PCM device...\n");

        struct pcm *pcm = pcm_open(card, device, PCM_OUT, &config);
        if (!pcm || !pcm_is_ready(pcm)) {
            fprintf(stderr, "[Daemon] Failed to open PCM: %s\n", pcm_get_error(pcm));
            if (pcm) pcm_close(pcm);
            close(client_fd);
            continue;
        }

        printf("[Daemon] ALSA streaming active! Low latency buffer: %u frames (~%.1f ms)\n",
               config.period_size * config.period_count,
               (config.period_size * config.period_count * 1000.0) / rate);

        while (running) {
            // Anti-bufferbloat check: if more than 4KB backlog accumulated, drain it
            int bytes_avail = 0;
            if (ioctl(client_fd, FIONREAD, &bytes_avail) == 0 && bytes_avail > 4096) {
                int to_drain = bytes_avail - chunk_bytes;
                while (to_drain > 0) {
                    int drain_size = to_drain > chunk_bytes ? chunk_bytes : to_drain;
                    ssize_t d = recv(client_fd, buffer, drain_size, 0);
                    if (d <= 0) break;
                    to_drain -= d;
                }
            }

            // Read exactly chunk_bytes
            int total_read = 0;
            while (total_read < chunk_bytes) {
                ssize_t n = recv(client_fd, buffer + total_read, chunk_bytes - total_read, 0);
                if (n <= 0) {
                    total_read = -1;
                    break;
                }
                total_read += n;
            }

            if (total_read < 0) {
                printf("[Daemon] Host disconnected.\n");
                break;
            }

            // Write directly to ALSA DAC
            int ret = pcm_write(pcm, buffer, chunk_bytes);
            if (ret != 0) {
                // If underrun, recover
                pcm_prepare(pcm);
            }
        }

        pcm_close(pcm);
        close(client_fd);
    }

    free(buffer);
    close(server_fd);
    printf("[Daemon] Stopped cleanly.\n");
    return 0;
}
