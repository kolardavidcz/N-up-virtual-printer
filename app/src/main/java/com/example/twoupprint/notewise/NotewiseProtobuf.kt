package com.example.twoupprint.notewise

import java.io.ByteArrayOutputStream

/**
 * Lightweight, zero-dependency Protocol Buffer wire format serializer and parser.
 *
 * Implements the standard Protobuf wire types needed for Notewise binary archives:
 *   - Wire 0: Varint (int32, int64, bool, enum)
 *   - Wire 1: 64-bit fixed (double IEEE 754)
 *   - Wire 2: Length-delimited (sub-messages, UTF-8 strings, byte arrays)
 *   - Wire 5: 32-bit fixed (float IEEE 754)
 */
class PbNode(
    val fieldNumber: Int,
    val wireType: Int,
    val value: Any
) {

    fun serialize(): ByteArray {
        val tag = (fieldNumber shl 3) or wireType
        val header = encodeVarint(tag.toLong())

        return when (wireType) {
            0 -> {
                val longVal = when (value) {
                    is Number -> value.toLong()
                    is Boolean -> if (value) 1L else 0L
                    else -> value.toString().toLong()
                }
                header + encodeVarint(longVal)
            }
            1 -> {
                val bytes = when (value) {
                    is Double -> encodeDouble(value)
                    is ByteArray -> value
                    else -> throw IllegalArgumentException("Expected Double or ByteArray for wire type 1, got ${value::class.java}")
                }
                header + bytes
            }
            2 -> {
                val body = when (value) {
                    is List<*> -> {
                        val bos = ByteArrayOutputStream()
                        for (item in value) {
                            if (item is PbNode) {
                                bos.write(item.serialize())
                            }
                        }
                        bos.toByteArray()
                    }
                    is ByteArray -> value
                    is String -> value.toByteArray(Charsets.UTF_8)
                    else -> throw IllegalArgumentException("Unsupported value type for wire type 2: ${value::class.java}")
                }
                header + encodeVarint(body.size.toLong()) + body
            }
            5 -> {
                val bytes = when (value) {
                    is Float -> encodeFloat(value)
                    is Number -> encodeFloat(value.toFloat())
                    is ByteArray -> value
                    else -> throw IllegalArgumentException("Expected Float or ByteArray for wire type 5, got ${value::class.java}")
                }
                header + bytes
            }
            else -> throw IllegalArgumentException("Unknown wire type $wireType for field $fieldNumber")
        }
    }

    override fun toString(): String {
        return "PbNode(fn=$fieldNumber, wt=$wireType, val=$value)"
    }

    companion object {

        fun encodeVarint(value: Long): ByteArray {
            val bos = ByteArrayOutputStream()
            var v = value
            while (true) {
                val toWrite = (v and 0x7F).toInt()
                v = v ushr 7
                if (v > 0) {
                    bos.write(toWrite or 0x80)
                } else {
                    bos.write(toWrite)
                    break
                }
            }
            return bos.toByteArray()
        }

        fun decodeVarint(data: ByteArray, startOffset: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var offset = startOffset
            while (offset < data.size) {
                val b = data[offset].toLong() and 0xFFL
                result = result or ((b and 0x7FL) shl shift)
                offset++
                if ((b and 0x80L) == 0L) {
                    break
                }
                shift += 7
            }
            return Pair(result, offset)
        }

        fun encodeFloat(value: Float): ByteArray {
            val bits = java.lang.Float.floatToRawIntBits(value)
            return byteArrayOf(
                (bits and 0xFF).toByte(),
                ((bits ushr 8) and 0xFF).toByte(),
                ((bits ushr 16) and 0xFF).toByte(),
                ((bits ushr 24) and 0xFF).toByte()
            )
        }

        fun encodeDouble(value: Double): ByteArray {
            val bits = java.lang.Double.doubleToRawLongBits(value)
            val buf = ByteArray(8)
            for (i in 0..7) {
                buf[i] = ((bits ushr (i * 8)) and 0xFF).toByte()
            }
            return buf
        }

        fun makeFloatNode(fieldNumber: Int, value: Float): PbNode {
            return PbNode(fieldNumber, 5, value)
        }

        fun parseProtobuf(data: ByteArray): List<PbNode> {
            val nodes = mutableListOf<PbNode>()
            var offset = 0
            while (offset < data.size) {
                val (tag, nextOffset) = try {
                    decodeVarint(data, offset)
                } catch (_: Exception) {
                    break
                }
                offset = nextOffset
                val fieldNumber = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                if (fieldNumber == 0) break

                when (wireType) {
                    0 -> {
                        val (value, afterVarint) = decodeVarint(data, offset)
                        offset = afterVarint
                        nodes.add(PbNode(fieldNumber, wireType, value))
                    }
                    1 -> {
                        if (offset + 8 > data.size) break
                        val slice = data.copyOfRange(offset, offset + 8)
                        offset += 8
                        nodes.add(PbNode(fieldNumber, wireType, slice))
                    }
                    2 -> {
                        val (length, afterLen) = decodeVarint(data, offset)
                        val len = length.toInt()
                        offset = afterLen
                        if (offset + len > data.size) break
                        val slice = data.copyOfRange(offset, offset + len)
                        offset += len

                        // Attempt to parse recursively as sub-message
                        var asSubMessage = false
                        try {
                            val subNodes = parseProtobuf(slice)
                            val bos = ByteArrayOutputStream()
                            for (n in subNodes) {
                                bos.write(n.serialize())
                            }
                            if (bos.toByteArray().contentEquals(slice)) {
                                nodes.add(PbNode(fieldNumber, wireType, subNodes))
                                asSubMessage = true
                            }
                        } catch (_: Exception) {
                            // Keep as raw bytes
                        }
                        if (!asSubMessage) {
                            nodes.add(PbNode(fieldNumber, wireType, slice))
                        }
                    }
                    5 -> {
                        if (offset + 4 > data.size) break
                        val slice = data.copyOfRange(offset, offset + 4)
                        offset += 4
                        val bits = (slice[0].toInt() and 0xFF) or
                                ((slice[1].toInt() and 0xFF) shl 8) or
                                ((slice[2].toInt() and 0xFF) shl 16) or
                                ((slice[3].toInt() and 0xFF) shl 24)
                        val f = java.lang.Float.intBitsToFloat(bits)
                        nodes.add(PbNode(fieldNumber, wireType, f))
                    }
                    else -> break
                }
            }
            return nodes
        }
    }
}
