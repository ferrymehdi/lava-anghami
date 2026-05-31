package org.ferrymehdi.plugin.anghami;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnghamiProtobufDecoder {
    private static final Logger log = LoggerFactory.getLogger(AnghamiProtobufDecoder.class);

    public static List<JSONObject> decodeSongBuffers(JSONArray buffers, String orderStr) {
        Map<String, JSONObject> songMap = new HashMap<>();

        for (int i = 0; i < buffers.length(); i++) {
            try {
                byte[] data = Base64.getDecoder().decode(buffers.getString(i));
                ProtoReader reader = new ProtoReader(data);

                while (reader.hasRemaining()) {
                    int tag = (int) reader.readVarint();
                    int fieldNo = tag >>> 3;
                    int wireType = tag & 7;

                    if (fieldNo == 2 && wireType == 2) {
                        int length = (int) reader.readVarint();
                        int end = Math.min(reader.position() + length, reader.limit());
                        String key = "";
                        JSONObject song = null;

                        try {
                            while (reader.position() < end && reader.hasRemaining()) {
                                int mapTag = (int) reader.readVarint();
                                int mapFieldNo = mapTag >>> 3;
                                int mapWireType = mapTag & 7;

                                if (mapFieldNo == 1) {
                                    if (mapWireType == 2) key = reader.readString();
                                    else reader.skipType(mapWireType);
                                } else if (mapFieldNo == 2) {
                                    if (mapWireType == 2) song = decodeSong(reader, (int) reader.readVarint());
                                    else reader.skipType(mapWireType);
                                } else {
                                    reader.skipType(mapWireType);
                                }
                            }
                            if (!key.isEmpty() && song != null) {
                                songMap.put(key, song);
                            }
                        } catch (Exception e) {
                            log.warn("Corrupted Map entry in buffer: {}", e.getMessage());
                        } finally {
                            reader.position(end);
                        }
                    } else {
                        reader.skipType(wireType);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to decode buffer", e);
            }
        }

        List<JSONObject> decodedSongs = new ArrayList<>();
        if (orderStr != null && !orderStr.isEmpty()) {
            String[] ids = orderStr.split(",");
            for (String songId : ids) {
                JSONObject song = songMap.get(songId.trim());
                if (song != null) decodedSongs.add(song);
            }
        } else {
            decodedSongs.addAll(songMap.values());
        }

        return decodedSongs;
    }

    private static JSONObject decodeSong(ProtoReader reader, int len) {
        int end = Math.min(reader.position() + len, reader.limit());
        JSONObject song = new JSONObject();
        song.put("duration_ms", 0L);

        try {
            while (reader.position() < end && reader.hasRemaining()) {
                int tag = (int) reader.readVarint();
                int fieldNo = tag >>> 3;
                int wireType = tag & 7;

                switch (fieldNo) {
                    case 1: if (wireType == 2) song.put("id", reader.readString()); else reader.skipType(wireType); break;
                    case 2: if (wireType == 2) song.put("title", reader.readString()); else reader.skipType(wireType); break;
                    case 3: if (wireType == 2) song.put("album", reader.readString()); else reader.skipType(wireType); break;
                    case 4: if (wireType == 2) song.put("albumID", reader.readString()); else reader.skipType(wireType); break;
                    case 5: if (wireType == 2) song.put("artist", reader.readString()); else reader.skipType(wireType); break;
                    case 6: if (wireType == 2) song.put("artistID", reader.readString()); else reader.skipType(wireType); break;
                    case 9:
                        if (wireType == 5) {
                            long ms = Math.round(reader.readFloat() * 1000.0);
                            song.put("duration_ms", ms);
                        } else if (wireType == 2) {
                            try {
                                long ms = Math.round(Float.parseFloat(reader.readString()) * 1000.0);
                                song.put("duration_ms", ms);
                            } catch (Exception ignored) {}
                        } else {
                            reader.skipType(wireType);
                        }
                        break;
                    case 10: if (wireType == 2) song.put("coverArt", reader.readString()); else reader.skipType(wireType); break;
                    default: reader.skipType(wireType); break;
                }
            }
        } catch (Exception e) {
            log.warn("Corrupted fields in song decoding: {}", e.getMessage());
        } finally {
            reader.position(end);
        }
        return song;
    }

    private static class ProtoReader {
        private final ByteBuffer buf;

        public ProtoReader(byte[] data) {
            this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        }

        public boolean hasRemaining() { return buf.hasRemaining(); }
        public int position() { return buf.position(); }
        public void position(int newPosition) { buf.position(newPosition); }
        public int limit() { return buf.limit(); }

        public long readVarint() {
            long value = 0;
            int shift = 0;
            while (hasRemaining()) {
                byte b = buf.get();
                value |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
                if (shift >= 70) throw new RuntimeException("Varint too long");
            }
            return value;
        }

        public String readString() {
            int len = (int) readVarint();
            if (len < 0 || buf.position() + len > buf.limit()) throw new RuntimeException("String bounds exceeded");
            byte[] bytes = new byte[len];
            buf.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        public float readFloat() {
            if (buf.position() + 4 > buf.limit()) throw new RuntimeException("Float bounds exceeded");
            return buf.getFloat();
        }

        public void skipType(int wireType) {
            switch (wireType) {
                case 0: readVarint(); break;
                case 1: buf.position(Math.min(buf.position() + 8, buf.limit())); break;
                case 2:
                    int len = (int) readVarint();
                    if (len > 0) buf.position(Math.min(buf.position() + len, buf.limit()));
                    break;
                case 3:
                    while (hasRemaining()) {
                        int tag = (int) readVarint();
                        if ((tag & 7) == 4) break;
                        skipType(tag & 7);
                    }
                    break;
                case 4: break;
                case 5: buf.position(Math.min(buf.position() + 4, buf.limit())); break;
                default: throw new RuntimeException("Unknown wire type: " + wireType);
            }
        }
    }
}
