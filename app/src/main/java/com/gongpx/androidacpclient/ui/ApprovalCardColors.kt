package com.gongpx.androidacpclient.ui

import androidx.compose.ui.graphics.Color
import com.gongpx.androidacpclient.data.model.ApprovalStatus

internal data class ApprovalCardColors(val container: Color, val content: Color)

internal fun approvalCardColors(status: ApprovalStatus, dark: Boolean): ApprovalCardColors =
    when (status) {
        ApprovalStatus.Pending -> if (dark) ApprovalCardColors(Color(0xFF3D3014), Color(0xFFF9D976))
            else ApprovalCardColors(Color(0xFFFFF4D6), Color(0xFF765300))
        ApprovalStatus.Submitting -> if (dark) ApprovalCardColors(Color(0xFF162E4D), Color(0xFFABCBFF))
            else ApprovalCardColors(Color(0xFFE8F0FE), Color(0xFF174EA6))
        ApprovalStatus.Approved -> if (dark) ApprovalCardColors(Color(0xFF143522), Color(0xFF9EE0B3))
            else ApprovalCardColors(Color(0xFFE6F4EA), Color(0xFF17643A))
        ApprovalStatus.Denied -> if (dark) ApprovalCardColors(Color(0xFF462323), Color(0xFFFFBCB5))
            else ApprovalCardColors(Color(0xFFFCE8E6), Color(0xFFA32622))
        ApprovalStatus.Expired -> if (dark) ApprovalCardColors(Color(0xFF442A1A), Color(0xFFF8C29B))
            else ApprovalCardColors(Color(0xFFFFEBDD), Color(0xFF8A3B00))
        ApprovalStatus.Unavailable -> if (dark) ApprovalCardColors(Color(0xFF2B3036), Color(0xFFC0CAD5))
            else ApprovalCardColors(Color(0xFFEDF0F3), Color(0xFF455466))
    }
