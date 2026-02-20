package zaujaani.roadsensebasic.util

import android.graphics.Color
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class VibrationChartController(private val chart: LineChart) {

    fun setupChart(
        thresholdBaik: Float,
        thresholdSedang: Float,
        thresholdRusakRingan: Float,
        colorBaik: Int,
        colorSedang: Int,
        colorRusakBerat: Int
    ) {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            legend.isEnabled = false
            xAxis.isEnabled = false

            axisLeft.apply {
                setDrawLabels(true)
                textColor = Color.WHITE
                textSize = 10f
                gridColor = Color.argb(80, 255, 255, 255)
                axisLineColor = Color.WHITE
                removeAllLimitLines()
                addLimitLine(
                    LimitLine(thresholdBaik, "Baik").apply {
                        lineColor = colorBaik
                        textColor = colorBaik
                        textSize = 9f
                        lineWidth = 1.5f
                    }
                )
                addLimitLine(
                    LimitLine(thresholdSedang, "Sedang").apply {
                        lineColor = colorSedang
                        textColor = colorSedang
                        textSize = 9f
                        lineWidth = 1.5f
                    }
                )
                addLimitLine(
                    LimitLine(thresholdRusakRingan, "Rusak Ringan").apply {
                        lineColor = colorRusakBerat
                        textColor = colorRusakBerat
                        textSize = 9f
                        lineWidth = 1.5f
                    }
                )
            }
            axisRight.isEnabled = false
            setBackgroundColor(Color.argb(200, 15, 15, 30))
            setNoDataText("Tunggu data...")
            setNoDataTextColor(Color.WHITE)
            setDrawBorders(true)
            setBorderColor(Color.argb(100, 255, 255, 255))
            setBorderWidth(0.5f)
        }
    }

    fun updateChart(history: List<Float>, thresholdBaik: Float, thresholdSedang: Float, thresholdRusakRingan: Float, colorBaik: Int, colorSedang: Int, colorRusakRingan: Int, colorRusakBerat: Int, onConditionUpdate: (String, Int) -> Unit) {
        if (history.isEmpty()) return

        val entries = history.mapIndexed { index, value -> Entry(index.toFloat(), value) }
        val lastVal = history.last()
        val lineColor = getConditionColor(lastVal, thresholdBaik, thresholdSedang, thresholdRusakRingan, colorBaik, colorSedang, colorRusakRingan, colorRusakBerat)

        val dataSet = LineDataSet(entries, "Z-Axis (g)").apply {
            color = lineColor
            setDrawCircles(false)
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillAlpha = 60
            fillColor = lineColor
            setDrawValues(false)
            highLightColor = Color.WHITE
        }

        val conditionText = when {
            lastVal < thresholdBaik -> "Baik"
            lastVal < thresholdSedang -> "Sedang"
            lastVal < thresholdRusakRingan -> "Rusak Ringan"
            else -> "Rusak Berat"
        }
        onConditionUpdate(conditionText, lineColor)

        chart.data = LineData(dataSet)
        chart.notifyDataSetChanged()
        chart.invalidate()
        chart.moveViewToX(entries.size.toFloat())
    }

    fun clear() {
        chart.clear()
        chart.setNoDataText("Tunggu data...")
        chart.invalidate()
    }

    private fun getConditionColor(vib: Float, thB: Float, thS: Float, thR: Float, colB: Int, colS: Int, colRR: Int, colRB: Int): Int = when {
        vib < thB -> colB
        vib < thS -> colS
        vib < thR -> colRR
        else -> colRB
    }
}