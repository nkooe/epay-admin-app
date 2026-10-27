package com.epay.admin

import org.junit.Assert.*
import org.junit.Test

class ManagementContractTest {
    @Test fun runningAndUnknownStatesAreDistinct(){
        assertEquals("运行中",managementState("running"))
        assertEquals("待核实",managementState("unknown"))
        assertEquals("等待处理",managementState("pending"))
        assertEquals("已停止",managementState("stopped"))
    }
    @Test fun complaintStatesDoNotInventSuccess(){
        assertEquals("待处理",complaintStatus(0));assertEquals("处理中",complaintStatus(1));assertEquals("已结束",complaintStatus(2))
        assertEquals("未知状态",complaintStatus(99))
    }
}
