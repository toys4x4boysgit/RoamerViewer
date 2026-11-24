package com.roamer.bmsmonitor

import kotlin.math.abs

object JkBmsProtocol {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val CMD_INFO: Byte = 0x97.toByte()
    const val CMD_CELL: Byte = 0x96.toByte()

    val ERROR_LABELS = listOf(
        "Charge over-temp",
        "Charge under-temp",
        "Coprocessor comms error",
        "Cell undervoltage",
        "Pack undervoltage",
        "Discharge over-current",
        "Discharge short circuit",
        "Discharge over-temp",
        "Wire resistance",
        "MOSFET over-temp",
        "Cell count mismatch",
        "Current sensor fault",
        "Cell overvoltage",
        "Pack overvoltage",
        "Charge over-current",
        "Charge short circuit"
    )

    data class BmsState(
        val connected: Boolean = false,
        val lastFrameMs: Long = 0L,
        val frames: Int = 0,
        val layout: Int = 0,
        val model: String = "",
        val hw: String = "",
        val sw: String = "",
        val nCells: Int = 0,
        val cells: FloatArray = FloatArray(32),
        val vTotal: Float = 0f,
        val current: Float = 0f,
        val power: Float = 0f,
        val t1: Float = 0f,
        val t2: Float = 0f,
        val tMos: Float = 0f,
        val cellMin: Float = 0f,
        val cellMax: Float = 0f,
        val cellAvg: Float = 0f,
        val cellDelta: Float = 0f,
        val balA: Float = 0f,
        val capRemain: Float = 0f,
        val capNominal: Float = 0f,
        val soc: Int = 0,
        val soh: Int = 0,
        val cycles: Long = 0L,
        val runtime: Long = 0L,
        val errors: Int = 0,
        val chg: Boolean = false,
        val dchg: Boolean = false,
        val balancing: Boolean = false
    )

    class FrameAssembler {
        private val buf = ByteArray(400)
        private var len = 0

        fun reset() {
            len = 0
        }

        fun feed(chunk: ByteArray): ByteArray? {
            val n = chunk.size
            val isHdr = n >= 4 &&
                chunk[0] == 0x55.toByte() &&
                chunk[1] == 0xAA.toByte() &&
                chunk[2] == 0xEB.toByte() &&
                chunk[3] == 0x90.toByte()

            if (isHdr) {
                len = 0
            } else if (len == 0) {
                return null
            }

            if (len + n > buf.size) {
                len = 0
                return null
            }

            System.arraycopy(chunk, 0, buf, len, n)
            len += n
            if (len < 300) return null

            var sum = 0
            for (k in 0 until 299) {
                sum = (sum + (buf[k].toInt() and 0xFF)) and 0xFF
            }
            len = 0
            return if (sum == (buf[299].toInt() and 0xFF)) {
                buf.copyOfRange(0, 300)
            } else {
                null
            }
        }
    }

    fun buildCmd(cmd: Byte): ByteArray {
        val out = ByteArray(20)
        out[0] = 0xAA.toByte()
        out[1] = 0x55.toByte()
        out[2] = 0x90.toByte()
        out[3] = 0xEB.toByte()
        out[4] = cmd
        var sum = 0
        for (k in 0 until 19) {
            sum = (sum + (out[k].toInt() and 0xFF)) and 0xFF
        }
        out[19] = sum.toByte()
        return out
    }

    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun i16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
    private fun u32(b: ByteArray, o: Int): Long =
        u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun i32(b: ByteArray, o: Int): Int =
        u8(b, o) or (u8(b, o + 1) shl 8) or (u8(b, o + 2) shl 16) or (u8(b, o + 3) shl 24)

    private fun plausible(f: ByteArray, nc: Int, off2: Int): Boolean {
        var sum = 0f
        var n = 0
        for (i in 0 until nc) {
            val v = u16(f, 6 + 2 * i) * 0.001f
            if (v > 5.0f) return false
            if (v > 0.1f) {
                sum += v
                n++
            }
        }
        if (n < 2) return false
        val tot = u32(f, 118 + off2) * 0.001f
        return abs(tot - sum) <= 0.03f * sum + 0.5f
    }

    private fun detectLayout(f: ByteArray, hint: Int, force: Int): Int {
        if (force == 24 || force == 32) return force
        if (hint == 32 && plausible(f, 32, 32)) return 32
        if (hint == 24 && plausible(f, 24, 0)) return 24
        if (plausible(f, 32, 32)) return 32
        if (plausible(f, 24, 0)) return 24
        return 0
    }

    private fun copyAscii(f: ByteArray, offset: Int, maxLen: Int): String {
        val sb = StringBuilder()
        for (i in 0 until maxLen) {
            val c = u8(f, offset + i)
            if (c < 32 || c >= 127) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    fun decodeInfo(prev: BmsState, f: ByteArray): BmsState {
        return prev.copy(
            model = copyAscii(f, 6, 16),
            hw = copyAscii(f, 22, 8),
            sw = copyAscii(f, 30, 8)
        )
    }

    fun decodeCell(prev: BmsState, f: ByteArray, nowMs: Long, force: Int = 0): BmsState {
        val layout = detectLayout(f, prev.layout, force)
        val nextFrames = prev.frames + 1
        if (layout == 0) {
            return prev.copy(frames = nextFrames, layout = 0)
        }

        val off = if (layout == 32) 16 else 0
        val off2 = off * 2
        val nc = if (layout == 32) 32 else 24

        val cells = FloatArray(32)
        var last = 0
        var cnt = 0
        var mn = 100f
        var mx = -100f
        var sum = 0f

        for (i in 0 until nc) {
            val v = u16(f, 6 + 2 * i) * 0.001f
            cells[i] = v
            if (v > 0.1f) {
                last = i + 1
                cnt++
                sum += v
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
        }

        val vTotal = u32(f, 118 + off2) * 0.001f
        val current = i32(f, 126 + off2) * 0.001f
        val tMos = if (layout == 32) i16(f, 112 + off2) * 0.1f else i16(f, 134 + off2) * 0.1f
        val errors = if (layout == 32) u16(f, 134 + off2) else u16(f, 136 + off2)

        return prev.copy(
            frames = nextFrames,
            layout = layout,
            nCells = last,
            cells = cells,
            cellMin = if (cnt > 0) mn else 0f,
            cellMax = if (cnt > 0) mx else 0f,
            cellAvg = if (cnt > 0) sum / cnt else 0f,
            cellDelta = if (cnt > 0) mx - mn else 0f,
            vTotal = vTotal,
            current = current,
            power = vTotal * current,
            t1 = i16(f, 130 + off2) * 0.1f,
            t2 = i16(f, 132 + off2) * 0.1f,
            tMos = tMos,
            errors = errors,
            balA = i16(f, 138 + off2) * 0.001f,
            balancing = u8(f, 140 + off2) != 0,
            soc = u8(f, 141 + off2),
            capRemain = u32(f, 142 + off2) * 0.001f,
            capNominal = u32(f, 146 + off2) * 0.001f,
            cycles = u32(f, 150 + off2),
            soh = u8(f, 158 + off2),
            runtime = u32(f, 162 + off2),
            chg = u8(f, 166 + off2) != 0,
            dchg = u8(f, 167 + off2) != 0,
            lastFrameMs = if (nowMs > 0) nowMs else 1L
        )
    }
}
