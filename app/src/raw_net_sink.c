#include "raw_net_sink.h"

#include <assert.h>
#include <inttypes.h>
#include <libavcodec/avcodec.h>

#include "util/binary.h"
#include "util/log.h"

/** Downcast packet_sink to raw_net_sink */
#define DOWNCAST(SINK) container_of(SINK, struct sc_raw_net_sink, packet_sink)

#define SC_PACKET_FLAG_CONFIG    (UINT64_C(1) << 63)
#define SC_PACKET_FLAG_KEY_FRAME (UINT64_C(1) << 62)
#define SC_PACKET_PTS_MASK       (SC_PACKET_FLAG_KEY_FRAME - 1)

#define SC_CODEC_ID_H264 UINT32_C(0x68323634)
#define SC_CODEC_ID_H265 UINT32_C(0x68323635)
#define SC_CODEC_ID_AV1  UINT32_C(0x00617631)

static uint32_t
avcodec_id_to_scrcpy_codec_id(enum AVCodecID id) {
    switch (id) {
        case AV_CODEC_ID_H264: return SC_CODEC_ID_H264;
        case AV_CODEC_ID_HEVC: return SC_CODEC_ID_H265;
#ifdef AV_CODEC_ID_AV1
        case AV_CODEC_ID_AV1:  return SC_CODEC_ID_AV1;
#endif
        default:               return 0;
    }
}

static bool
sc_raw_net_sink_open(struct sc_packet_sink *base, AVCodecContext *ctx) {
    struct sc_raw_net_sink *sink = DOWNCAST(base);

    LOGI("raw-net-sink: waiting for TCP client on 127.0.0.1:%" PRIu16,
         sink->port);

    sc_socket client = net_accept(sink->listener);
    if (client == SC_SOCKET_NONE) {
        LOGE("raw-net-sink: accept() failed");
        return false;
    }
    sink->client = client;

    // Disable Nagle — plugin wants packets as soon as they arrive.
    net_set_tcp_nodelay(client, true);

    uint32_t codec_id = avcodec_id_to_scrcpy_codec_id(ctx->codec_id);
    if (!codec_id) {
        LOGE("raw-net-sink: unsupported codec id %d", (int) ctx->codec_id);
        return false;
    }

    uint8_t prelude[12];
    sc_write32be(prelude, codec_id);
    sc_write32be(prelude + 4, (uint32_t) ctx->width);
    sc_write32be(prelude + 8, (uint32_t) ctx->height);

    ssize_t w = net_send_all(client, prelude, sizeof(prelude));
    if (w < (ssize_t) sizeof(prelude)) {
        LOGE("raw-net-sink: could not send prelude");
        return false;
    }

    LOGI("raw-net-sink: client connected, prelude sent "
         "(codec=0x%08" PRIx32 " %dx%d)",
         codec_id, ctx->width, ctx->height);
    return true;
}

static void
sc_raw_net_sink_close(struct sc_packet_sink *base) {
    struct sc_raw_net_sink *sink = DOWNCAST(base);
    if (sink->client != SC_SOCKET_NONE) {
        net_close(sink->client);
        sink->client = SC_SOCKET_NONE;
    }
}

static bool
sc_raw_net_sink_push(struct sc_packet_sink *base, const AVPacket *packet) {
    struct sc_raw_net_sink *sink = DOWNCAST(base);

    if (sink->client == SC_SOCKET_NONE) {
        return false;
    }

    uint64_t pts_flags;
    if (packet->pts == AV_NOPTS_VALUE) {
        pts_flags = SC_PACKET_FLAG_CONFIG;
    } else {
        pts_flags = ((uint64_t) packet->pts) & SC_PACKET_PTS_MASK;
    }
    if (packet->flags & AV_PKT_FLAG_KEY) {
        pts_flags |= SC_PACKET_FLAG_KEY_FRAME;
    }

    uint8_t header[12];
    sc_write64be(header, pts_flags);
    sc_write32be(header + 8, (uint32_t) packet->size);

    ssize_t w = net_send_all(sink->client, header, sizeof(header));
    if (w < (ssize_t) sizeof(header)) {
        LOGE("raw-net-sink: send header failed");
        return false;
    }

    w = net_send_all(sink->client, packet->data, packet->size);
    if (w < packet->size) {
        LOGE("raw-net-sink: send payload failed");
        return false;
    }
    return true;
}

static const struct sc_packet_sink_ops sc_raw_net_sink_ops = {
    .open = sc_raw_net_sink_open,
    .close = sc_raw_net_sink_close,
    .push = sc_raw_net_sink_push,
    .disable = NULL,
};

bool
sc_raw_net_sink_init(struct sc_raw_net_sink *sink, uint16_t port) {
    sink->port = port;
    sink->client = SC_SOCKET_NONE;
    sink->packet_sink.ops = &sc_raw_net_sink_ops;

    sink->listener = net_socket();
    if (sink->listener == SC_SOCKET_NONE) {
        LOGE("raw-net-sink: could not create listener socket");
        return false;
    }

    if (!net_listen(sink->listener, IPV4_LOCALHOST, port, 1)) {
        LOGE("raw-net-sink: could not listen on 127.0.0.1:%" PRIu16, port);
        net_close(sink->listener);
        sink->listener = SC_SOCKET_NONE;
        return false;
    }

    LOGI("raw-net-sink: listening on 127.0.0.1:%" PRIu16, port);
    return true;
}

void
sc_raw_net_sink_destroy(struct sc_raw_net_sink *sink) {
    if (sink->client != SC_SOCKET_NONE) {
        net_close(sink->client);
        sink->client = SC_SOCKET_NONE;
    }
    if (sink->listener != SC_SOCKET_NONE) {
        net_close(sink->listener);
        sink->listener = SC_SOCKET_NONE;
    }
}
