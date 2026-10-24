package com.bauman.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LksTest {
    private val current = """{"data":{"term":1,"weekName":"знаменатель","weekNumber":8,"semesterEnds":"2027-01-05","weekShortName":"зн","semesterStarts":"2026-09-01"},"date":"2026-10-24T17:45:53.991+03:00"}"""

    // Урезанный ответ progress: структура настоящая, ФИО и id заменены.
    private val progress = """
        {"data":{"year":"2026","student":{"groupId":"g","stageId":"s","disabled":"e","lastName":"Иванов","firstName":"Иван","middleName":"Иванович","disciplines":[
        {"title":"Аналитическая геометрия","controls":[
          {"type":"ДЗ","week":"7","stage":"1","setDate":"2026-10-15 16:33:02","controlId":"a1","passStatus":"early"},
          {"type":"РК","week":"8","stage":null,"setDate":null,"controlId":"a2"},
          {"type":"М","week":"9","stage":null,"points":{"point_3":"25","point_4":"30","point_5":"36","point_all":"43"},"setDate":null,"controlId":"a3"}],
         "seminars":[
          {"type":"СЗ","week":"1","stage":"12","setDate":"2026-10-01 16:44:18","controlId":"a4","passStatus":"late"},
          {"type":"СЗ","week":"2","stage":null,"setDate":null,"controlId":"a5"},
          {"type":"СЗ","week":"3","stage":"12","setDate":"2026-10-01 16:44:18","controlId":"a6","passStatus":"in time"}],
         "laboratory":[],"disciplineUuid":"d1"},
        {"title":"Информатика","controls":[
          {"type":"РК","week":"3","stage":"3","setDate":"2026-10-07 17:17:25","controlId":"b1","passStatus":"late"},
          {"type":"РК","week":"6","stage":"1","setDate":"2026-10-05 20:07:14","controlId":"b2","passStatus":"early"},
          {"type":"М","week":"4","stage":"10","value":"9","points":{"point_3":"9","point_4":"10","point_5":"12","point_all":"15"},"setDate":"2026-10-07 17:17:31","controlId":"b3","passStatus":"in time"},
          {"type":"М","week":"8","stage":"10","value":"12","points":{"point_3":"12","point_4":"14","point_5":"17","point_all":"20"},"setDate":"2026-10-15 13:13:43","controlId":"b4","passStatus":"early"}],
         "seminars":[
          {"type":"СЗ","week":"1","stage":"4","setDate":"2026-09-03 12:35:28","controlId":"b5","passStatus":"early"},
          {"type":"СЗ","week":"5","stage":"12","setDate":"2026-10-01 12:25:45","controlId":"b6","passStatus":"early"}],
         "laboratory":[
          {"type":"ЛР","week":"1","stage":"3","setDate":"2026-09-17 14:21:17","controlId":"b7","passStatus":"in time"},
          {"type":"ЛР","week":"9","stage":"1","setDate":"2026-09-02 18:18:08","controlId":"b8","passStatus":"early"}],
         "disciplineUuid":"d2"}]},"semester":"1"},"date":"2026-10-24T13:37:17.836+03:00"}
    """.trimIndent()

    @Test
    fun parse() {
        val l = LksParse.parse(progress, current)
        assertEquals(8, l.week)
        assertEquals("знаменатель", l.weekName)
        assertEquals("24.10 13:37", l.asOf)
        val ag = l.disciplines[0]
        assertEquals("АГ", ag.name)
        assertEquals(1, ag.modules.size)
        assertNull(ag.modules[0].value)
        assertEquals(43.0, ag.modules[0].max!!, 0.0)
        val inf = l.disciplines[1]
        assertEquals(listOf(4, 8), inf.modules.map { it.week })
        assertEquals(21.0, inf.got, 0.0)
        assertEquals(35.0, inf.total, 0.0)
        assertEquals(3, inf.modules[0].grade)
        assertEquals(3, inf.modules[1].grade)
        assertEquals(14.0, inf.modules[1].threshold(4)!!, 0.0)
        assertEquals("05.10", inf.tasks[1].setDay)
    }

    @Test
    fun hangingAndClaude() {
        val l = LksParse.parse(progress, current)
        // АГ: ДЗ 7 выдано и не сдано. Информатика: РК 6 висит; ЛР 9 выдана заранее — ещё не долг.
        assertEquals(listOf("ДЗ 7"), l.disciplines[0].hanging(8).map { "${it.type} ${it.week}" })
        assertEquals(listOf("РК 6"), l.disciplines[1].hanging(8).map { "${it.type} ${it.week}" })
        val data = Data.EMPTY.copy(lks = l)
        val asks = Plan.asks(data, LocalDateTime.of(2026, 10, 24, 18, 0))
        assertEquals(listOf("ЛКС", "ЛКС"), asks.map { it.kind })
        val text = Plan.forClaude(asks, LocalDateTime.of(2026, 10, 24, 18, 0))
        assertTrue(text.contains("\n2. ЛКС · Информатика\nРК, неделя 6\nСейчас: в ЛКС: выдано 05.10, не сдано\nСдал? Когда и сколько баллов?\nОтвет:\n"))
    }
}
