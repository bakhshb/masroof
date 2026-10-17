package com.baraa.masroof.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import com.baraa.masroof.ui.theme.MasroofIconSizes

/** Lucide outline geometry used by the approved reference. See docs/licenses/lucide.txt. */
object MasroofOutlineIcons {
    val Wallet: ImageVector by lazy { outline("Wallet", listOf("M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1", "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4")) }
    val Info: ImageVector by lazy { outline("Info", listOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 16v-4", "M12 8h.01")) }
    val CircleAlert: ImageVector by lazy { outline("CircleAlert", listOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 8L12 12", "M12 16L12.01 16")) }
    val TrendingDown: ImageVector by lazy { outline("TrendingDown", listOf("M16 17h6v-6", "m22 17-8.5-8.5-5 5L2 7")) }
    val TrendingUp: ImageVector by lazy { outline("TrendingUp", listOf("M16 7h6v6", "m22 7-8.5 8.5-5-5L2 17")) }
    val Undo2: ImageVector by lazy { outline("Undo2", listOf("M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11")) }
    val ArrowRightLeft: ImageVector by lazy { outline("ArrowRightLeft", listOf("m16 3 4 4-4 4", "M20 7H4", "m8 21-4-4 4-4", "M4 17h16")) }
    val Landmark: ImageVector by lazy { outline("Landmark", listOf("M10 18v-7", "M11.12 2.198a2 2 0 0 1 1.76.006l7.866 3.847c.476.233.31.949-.22.949H3.474c-.53 0-.695-.716-.22-.949z", "M14 18v-7", "M18 18v-7", "M3 22h18", "M6 18v-7")) }
    val List: ImageVector by lazy { outline("List", listOf("M3 5h.01", "M3 12h.01", "M3 19h.01", "M8 5h13", "M8 12h13", "M8 19h13")) }
    val ShieldCheck: ImageVector by lazy { outline("ShieldCheck", listOf("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4")) }
    val Bell: ImageVector by lazy { outline("Bell", listOf("M10.268 21a2 2 0 0 0 3.464 0", "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326")) }
    val RefreshCw: ImageVector by lazy { outline("RefreshCw", listOf("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5")) }
    val ChevronLeft: ImageVector by lazy { outline("ChevronLeft", listOf("m15 18-6-6 6-6")) }
    val ChevronRight: ImageVector by lazy { outline("ChevronRight", listOf("m9 18 6-6-6-6")) }
    val CalendarDays: ImageVector by lazy { outline("CalendarDays", listOf("M8 2v4", "M16 2v4", "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z", "M3 10h18", "M8 14h.01", "M12 14h.01", "M16 14h.01", "M8 18h.01", "M12 18h.01", "M16 18h.01")) }
    val ArrowDownLeft: ImageVector by lazy { outline("ArrowDownLeft", listOf("M17 7 7 17", "M17 17H7V7")) }
    val ArrowUpRight: ImageVector by lazy { outline("ArrowUpRight", listOf("M7 7h10v10", "M7 17 17 7")) }
    val CreditCard: ImageVector by lazy { outline("CreditCard", listOf("M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2Z", "M2 10L22 10")) }
    val Banknote: ImageVector by lazy { outline("Banknote", listOf("M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2Z", "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M6 12h.01M18 12h.01")) }
    val MessageSquare: ImageVector by lazy { outline("MessageSquare", listOf("M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z")) }
    val CalendarCheck: ImageVector by lazy { outline("CalendarCheck", listOf("M8 2v4", "M16 2v4", "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z", "M3 10h18", "m9 16 2 2 4-4")) }
    val ChartLine: ImageVector by lazy { outline("ChartLine", listOf("M3 3v16a2 2 0 0 0 2 2h16", "m19 9-5 5-4-4-3 3")) }
    val Store: ImageVector by lazy { outline("Store", listOf("M15 21v-5a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v5", "M17.774 10.31a1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.451 0 1.12 1.12 0 0 0-1.548 0 2.5 2.5 0 0 1-3.452 0 1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.77-3.248l2.889-4.184A2 2 0 0 1 7 2h10a2 2 0 0 1 1.653.873l2.895 4.192a2.5 2.5 0 0 1-3.774 3.244", "M4 10.95V19a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8.05")) }
    val User: ImageVector by lazy { outline("User", listOf("M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M8 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0")) }
    val Link: ImageVector by lazy { outline("Link", listOf("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")) }
    val CircleCheck: ImageVector by lazy { outline("CircleCheck", listOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "m9 12 2 2 4-4")) }
    val TriangleAlert: ImageVector by lazy { outline("TriangleAlert", listOf("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")) }
    val Settings: ImageVector by lazy { outline("Settings", listOf("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")) }
    val SlidersHorizontal: ImageVector by lazy { outline("SlidersHorizontal", listOf("M10 5H3", "M12 19H3", "M14 3v4", "M16 17v4", "M21 12h-9", "M21 19h-5", "M21 5h-7", "M8 10v4", "M8 12H3")) }
    val HandCoins: ImageVector by lazy { outline("HandCoins", listOf("M11 15h2a2 2 0 1 0 0-4h-3c-.6 0-1.1.2-1.4.6L3 17", "m7 21 1.6-1.4c.3-.4.8-.6 1.4-.6h4c1.1 0 2.1-.4 2.8-1.2l4.6-4.4a2 2 0 0 0-2.75-2.91l-4.2 3.9", "m2 16 6 6", "M13.1 9a2.9 2.9 0 1 0 5.8 0a2.9 2.9 0 1 0 -5.8 0", "M3 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")) }
    val ShoppingCart: ImageVector by lazy { outline("ShoppingCart", listOf("M7 21a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M18 21a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12")) }
    val Upload: ImageVector by lazy { outline("Upload", listOf("M12 3v12", "m17 8-5-5-5 5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4")) }
    val Download: ImageVector by lazy { outline("Download", listOf("M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5")) }
    val Moon: ImageVector by lazy { outline("Moon", listOf("M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401")) }
    val Share2: ImageVector by lazy { outline("Share2", listOf("M15 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M3 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M15 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M8.59 13.51L15.42 17.49", "M15.41 6.51L8.59 10.49")) }
    val LayoutDashboard: ImageVector by lazy { outline("LayoutDashboard", listOf("M4 3h5a1 1 0 0 1 1 1v7a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-7a1 1 0 0 1 1 -1Z", "M15 3h5a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M15 12h5a1 1 0 0 1 1 1v7a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-7a1 1 0 0 1 1 -1Z", "M4 16h5a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z")) }
    val Sun: ImageVector by lazy { outline("Sun", listOf("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41", "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41")) }
    val Monitor: ImageVector by lazy { outline("Monitor", listOf("M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2Z", "M8 21L16 21", "M12 17L12 21")) }
    val DatabaseBackup: ImageVector by lazy { outline("DatabaseBackup", listOf("M3 5a9 3 0 1 0 18 0a9 3 0 1 0 -18 0", "M3 12a9 3 0 0 0 5 2.69", "M21 9.3V5", "M3 5v14a9 3 0 0 0 6.47 2.88", "M12 12v4h4", "M13 20a5 5 0 0 0 9-3 4.5 4.5 0 0 0-4.5-4.5c-1.33 0-2.54.54-3.41 1.41L12 16")) }
    val ScrollText: ImageVector by lazy { outline("ScrollText", listOf("M15 12h-5", "M15 8h-5", "M19 17V5a2 2 0 0 0-2-2H4", "M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H11a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3")) }
    val Clock3: ImageVector by lazy { outline("Clock3", listOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 6v6h4")) }
    val ArrowRight: ImageVector by lazy { outline("ArrowRight", listOf("M5 12h14", "m12 5 7 7-7 7")) }
    val Receipt: ImageVector by lazy { outline("Receipt", listOf("M12 17V7", "M16 8h-6a2 2 0 0 0 0 4h4a2 2 0 0 1 0 4H8", "M4 3a1 1 0 0 1 1-1 1.3 1.3 0 0 1 .7.2l.933.6a1.3 1.3 0 0 0 1.4 0l.934-.6a1.3 1.3 0 0 1 1.4 0l.933.6a1.3 1.3 0 0 0 1.4 0l.933-.6a1.3 1.3 0 0 1 1.4 0l.934.6a1.3 1.3 0 0 0 1.4 0l.933-.6A1.3 1.3 0 0 1 19 2a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1 1.3 1.3 0 0 1-.7-.2l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.934.6a1.3 1.3 0 0 1-1.4 0l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-1.4 0l-.934-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-.7.2 1 1 0 0 1-1-1z")) }
    val ReceiptText: ImageVector by lazy { outline("ReceiptText", listOf("M13 16H8", "M14 8H8", "M16 12H8", "M4 3a1 1 0 0 1 1-1 1.3 1.3 0 0 1 .7.2l.933.6a1.3 1.3 0 0 0 1.4 0l.934-.6a1.3 1.3 0 0 1 1.4 0l.933.6a1.3 1.3 0 0 0 1.4 0l.933-.6a1.3 1.3 0 0 1 1.4 0l.934.6a1.3 1.3 0 0 0 1.4 0l.933-.6A1.3 1.3 0 0 1 19 2a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1 1.3 1.3 0 0 1-.7-.2l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.934.6a1.3 1.3 0 0 1-1.4 0l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-1.4 0l-.934-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-.7.2 1 1 0 0 1-1-1z")) }
    val ShoppingBag: ImageVector by lazy { outline("ShoppingBag", listOf("M16 10a4 4 0 0 1-8 0", "M3.103 6.034h17.794", "M3.4 5.467a2 2 0 0 0-.4 1.2V20a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6.667a2 2 0 0 0-.4-1.2l-2-2.667A2 2 0 0 0 17 2H7a2 2 0 0 0-1.6.8z")) }
    val RotateCcw: ImageVector by lazy { outline("RotateCcw", listOf("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")) }
    val ArrowLeftRight: ImageVector by lazy { outline("ArrowLeftRight", listOf("M8 3 4 7l4 4", "M4 7h16", "m16 21 4-4-4-4", "M20 17H4")) }
    val CalendarClock: ImageVector by lazy { outline("CalendarClock", listOf("M16 14v2.2l1.6 1", "M16 2v4", "M21 7.5V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h3.5", "M3 10h5", "M8 2v4", "M10 16a6 6 0 1 0 12 0a6 6 0 1 0 -12 0")) }
    val Palette: ImageVector by lazy { outline("Palette", listOf("M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z", "M13 6.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0", "M17 10.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0", "M6 12.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0", "M8 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0")) }
}

private fun outline(name: String, paths: List<String>): ImageVector {
    val builder = ImageVector.Builder(name, MasroofIconSizes.xl, MasroofIconSizes.xl, 24f, 24f)
    paths.forEach { data -> builder.addPath(pathData = PathParser().parsePathString(data).toNodes(),
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    return builder.build()
}
