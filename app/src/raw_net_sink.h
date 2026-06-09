#ifndef SC_RAW_NET_SINK_H
#define SC_RAW_NET_SINK_H

#include "common.h"

#include <stdbool.h>
#include <stdint.h>

#include "trait/packet_sink.h"
#include "util/net.h"

/**
 * Packet sink that forwards every demuxed packet over loopback TCP in the
 * scrcpy wire format. Intended for embedders (e.g. OBS plugin) that want raw
 * encoded bytes without the SDL/display/recorder stack in the way.
 *
 * Wire format:
 *
 *     [codec_id: u32 BE][width: u32 BE][height: u32 BE]  // once, on open
 *     [pts_flags: u64 BE][size: u32 BE][NAL bytes ...]    // per packet
 *
 * pts_flags encoding matches demuxer.c:
 *   bit 63 = config (SPS/PPS extradata)
 *   bit 62 = keyframe
 *   bits 61..0 = pts (or AV_NOPTS for config packets)
 *
 * codec_id is the 4-char ASCII used by scrcpy's protocol
 * ("h264"/"h265"/"av1"). Derived from the AVCodecContext at open time.
 */
struct sc_raw_net_sink {
    struct sc_packet_sink packet_sink;

    uint16_t port;
    sc_socket listener;
    sc_socket client;
};

bool
sc_raw_net_sink_init(struct sc_raw_net_sink *sink, uint16_t port);

void
sc_raw_net_sink_destroy(struct sc_raw_net_sink *sink);

#endif
