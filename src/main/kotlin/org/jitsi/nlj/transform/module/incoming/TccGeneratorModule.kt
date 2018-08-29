/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.nlj.transform.module.incoming

import org.jitsi.nlj.transform.module.Module
import org.jitsi.nlj.transform.module.forEachAs
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import org.jitsi.rtp.SrtpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.Tcc
import unsigned.toUInt

class TccGeneratorModule(
    private val onTccPacketReady: (RtcpPacket) -> Unit = {}
) : Module("TCC generator") {
    private var tccExtensionId: Int? = null
    private var currTccSeqNum: Int = 0
    private var currTcc: Tcc = Tcc(feedbackPacketCount = currTccSeqNum++)
    private var tempDetectedSsrc: Long? = null
    private var numTccSent: Int = 0

    override fun doProcessPackets(p: List<Packet>) {
        val now = System.currentTimeMillis()
        tccExtensionId?.let { tccExtId ->
            p.forEachAs<SrtpPacket> {
                it.header.getExtension(tccExtId).let currPkt@ { tccExt ->
                    //TODO: check if it's a one byte or two byte ext?
                    val tccSeqNum = tccExt?.data?.getShort(0)?.toUInt() ?: return@currPkt
                    addPacket(tccSeqNum, now)
                }
                if (tempDetectedSsrc == null) {
                    tempDetectedSsrc = it.header.ssrc
                }
            }
        }
        next(p)
    }

    private fun addPacket(tccSeqNum: Int, timestamp: Long) {
        currTcc.addPacket(tccSeqNum, timestamp)

        if (isTccReadyToSend()) {
            val pkt = RtcpFbPacket(feedbackControlInformation = currTcc)
            pkt.mediaSourceSsrc = tempDetectedSsrc!!
            onTccPacketReady(pkt)
            numTccSent++
            // Create a new TCC instance for the next set of information
            currTcc = Tcc(feedbackPacketCount = currTccSeqNum++)
        }
    }

    private fun isTccReadyToSend(): Boolean = currTcc.packetInfo.size >= 20

    override fun getStatsString(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStatsString(indent))
            appendLnIndent(indent + 2, "num tcc packets sent: $numTccSent")
            toString()
        }
    }
}
