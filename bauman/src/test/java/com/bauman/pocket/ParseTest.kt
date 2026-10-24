package com.bauman.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ParseTest {
    private val schedule = File("src/main/assets/schedule.md").readText()
    private val lks = File("src/main/assets/lks-plan.md").readText()

    private val deadlines = """
        | Когда | Предмет | Что | Статус |
        |---|---|---|---|
        | 2026-10-28 17:35 | informatika | Практикум №9 — сдать | готов 14.10 |
        | 2026-10-08 14:05 | informatika | Практикум №5 | ⏳ отправил, ждём ответ |
        | неделя 9 | analiticheskaya-geometriya | ДЗ 1, **вар. 2** | эталон готов |
        | — | admin | Справка \| за пропуск | уточнить |
    """.trimIndent()

    private val rk = """
        | Когда | Предмет | Что | Темы / формат | Макс | Статус |
        |---|---|---|---|---|---|
        | 2026-11-17 08:30 | himiya | Контроль модуля 2 | равновесие, кинетика | 5 | дата расчётная |
        | — | sotsiologiya | РК | — | 10 | ✅ 10/10 |
    """.trimIndent()

    private val brs = """
        # БРС

        ## himiya — Химия
        Семестр: максимум 100 · порог 60.

        | Работа | Макс | Получено | Дата |
        |---|---|---|---|
        | ЛР «ОВР» | 3 | 2,7 | 08.09 |
        | ДЗ 1 | 15 | 15 | 06.10 |
        | Защита М1 | 8 | — | 13.10 |

        ## informatika — Информатика
        | Работа | Макс | Получено | Дата |
        |---|---|---|---|
        | Практикум №1 | — | зачёт | 03.09 |
        | РК1 | 6 | — | 24.09 |
    """.trimIndent()

    private fun data(): Data {
        val (num, den) = Md.schedule(schedule)
        return Data(num, den, Md.items(deadlines, Source.DEADLINE) + Md.items(rk, Source.RK), Md.lks(lks), Md.grades(brs), true, 0)
    }

    @Test
    fun weeksAndParity() {
        assertEquals(1, Semester.weekOf(LocalDate.of(2026, 8, 31)))
        assertEquals(8, Semester.weekOf(LocalDate.of(2026, 10, 24)))
        assertTrue(Semester.isNumerator(9))
        assertFalse(Semester.isNumerator(8))
        assertEquals(LocalDate.of(2026, 11, 5), Semester.nextStudyDay(LocalDate.of(2026, 11, 3)))
    }

    @Test
    fun scheduleGrid() {
        val (num, den) = Md.schedule(schedule)
        assertEquals(19, num.sumOf { it.size })
        assertEquals(19, den.sumOf { it.size })
        val mon = num[0]
        assertEquals("Матан", mon[0].title)
        assertEquals("лекция", mon[0].kind)
        assertEquals("Велищанский", mon[0].teacher)
        assertEquals("324х", mon[0].room)
        // Плавающая третья пара берёт время из ячейки.
        assertEquals(LocalTime.of(11, 50), mon[2].start)
        assertEquals("Информатика", mon[2].title)
        assertEquals("Матан", den[0][2].title)
        // ФКиС в четверг: место — заметка, а не аудитория.
        val pe = num[3].first { it.title == "ФКиС" }
        assertNull(pe.room)
        assertEquals("Измайлово, начало 16:35", pe.note)
        assertTrue(num[4].single().selfStudy)
        assertEquals("каф. ФН5", num[1][0].room)
    }

    @Test
    fun items() {
        val items = data().items
        val p9 = items.first { it.what.startsWith("Практикум №9") }
        assertEquals(LocalDate.of(2026, 10, 28), p9.date)
        assertEquals(LocalTime.of(17, 35), p9.time)
        val ag = items.first { it.subj == "analiticheskaya-geometriya" }
        assertEquals(9, ag.week)
        assertEquals("ДЗ 1, вар. 2", ag.what)
        assertEquals("Справка | за пропуск", items.first { it.subj == "admin" }.what)
        val chem = items.first { it.subj == "himiya" }
        assertEquals("равновесие, кинетика", chem.topics)
        assertEquals("5", chem.max)
        assertTrue(items.first { it.subj == "sotsiologiya" }.done)
    }

    @Test
    fun buckets() {
        val at = LocalDateTime.of(2026, 10, 24, 9, 0)
        val b = Plan.buckets(data(), at)
        assertEquals(listOf("Практикум №5"), b.waiting.map { it.what })
        assertEquals(listOf("Практикум №9 — сдать", "ДЗ 1, вар. 2", "Контроль модуля 2"), b.upcoming.map { it.what })
        assertEquals(1, b.undated.size)
        assertTrue(b.overdue.isEmpty())
        assertEquals("через 4 дня", Plan.until(b.upcoming[0], at))
        assertEquals("на следующей неделе", Plan.until(b.upcoming[1], at))
    }

    @Test
    fun nowAndNext() {
        val d = data()
        // Сб 24.10, 10:30 — идёт история (2-я пара).
        val during = Plan.now(d, LocalDateTime.of(2026, 10, 24, 10, 30)) as Plan.Now.During
        assertEquals("История России", during.lesson.title)
        assertEquals(70, during.left)
        // После последней пары субботы следующая — понедельник 08:30.
        val next = Plan.now(d, LocalDateTime.of(2026, 10, 24, 16, 0)) as Plan.Now.Next
        assertEquals(LocalDate.of(2026, 10, 26), next.date)
        assertEquals("Матан", next.lesson.title)
        assertEquals(LocalDate.of(2026, 10, 26), Plan.focusDay(d, LocalDateTime.of(2026, 10, 24, 16, 0)))
        // Пятница: только самостоятельная — для «сейчас» её нет.
        val fri = Plan.now(d, LocalDateTime.of(2026, 10, 30, 10, 30)) as Plan.Now.Next
        assertEquals(LocalDate.of(2026, 10, 31), fri.date)
    }

    @Test
    fun grades() {
        val g = Md.grades(brs)
        val chem = g.first { it.slug == "himiya" }
        assertEquals("Химия", chem.name)
        assertEquals(17.7, chem.got, 1e-9)
        assertEquals(18.0, chem.max, 1e-9)
        assertEquals(60, chem.threshold)
        assertEquals(3, chem.works.size)
        assertFalse(chem.low)
        val inf = g.first { it.slug == "informatika" }
        assertEquals(1, inf.passes)
        assertEquals(0.0, inf.max, 1e-9)
    }

    @Test
    fun lksGrid() {
        val marks = Md.lks(lks)
        assertEquals(setOf("analiticheskaya-geometriya", "matan", "upevm"), marks.getValue(8).map { it.slug }.toSet())
        assertTrue(marks.getValue(10).any { it.slug == "fizkultura" && it.hot && it.mark == "М 8" })
    }

    @Test
    fun reminders() {
        val r = Plan.reminders(data()).filter { it.item.what.startsWith("Практикум №9") }
        assertEquals(listOf(LocalDateTime.of(2026, 10, 27, 20, 0), LocalDateTime.of(2026, 10, 28, 15, 35)), r.map { it.at })
    }

    @Test
    fun plural() {
        assertEquals("1 день", Ru.days(1))
        assertEquals("3 дня", Ru.days(3))
        assertEquals("11 дней", Ru.days(11))
        assertEquals("21 день", Ru.days(21))
        assertEquals("1 ч 05 мин", Ru.duration(65))
    }

    @Test
    fun teacherInitials() {
        val contacts = """
            | Предмет | ФИО | Роль |
            |---------|-----|------|
            | Математический анализ | Пинчук Ирина Александровна | семинарист |
            | Химия | Елапов Александр Александрович | лабораторные |
            | Начертательная геометрия | Палий Наталья Викторовна | лектор |
            | Начертательная геометрия | Михеенкова | семинары |
            | Иностранный язык | **?** | семинарист |
        """.trimIndent()
        val t = Md.teachers(contacts)
        assertEquals("Пинчук И.А.", Md.shortTeacher("Пинчук", t))
        assertEquals("Елапов А.А.", Md.shortTeacher("Елапов А.А.", t))
        assertEquals("Палий Н.В., Михеенкова", Md.shortTeacher("Палий, Михеенкова", t))
        assertEquals("Коган", Md.shortTeacher("Коган", t))
    }

    @Test
    fun answerSheetForClaude() {
        val at = LocalDateTime.of(2026, 11, 1, 12, 0)
        val asks = Plan.asks(data(), at)
        assertEquals(listOf("Событие", "Событие", "Результат", "Вопрос"), asks.map { it.kind })
        val text = Plan.forClaude(asks, at)
        assertTrue(text.startsWith("Бауманка → Claude, 01.11.2026\n"))
        assertTrue(text.contains("\n1. Событие · Информатика · ср, 28 окт, 17:35\nПрактикум №9 — сдать\nСейчас: готов 14.10\nКак прошло?\nОтвет:\n"))
        assertTrue(text.contains("\n4. Вопрос · Админка\nСправка | за пропуск\n"))
        assertEquals(4, Regex("Ответ:").findAll(text).count())
    }
}
