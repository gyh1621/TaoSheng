package app.resound.data

import app.resound.capture.VoiceInputDedup

suspend fun TranscribeDao.deleteFailed(id: Long): Boolean = transaction {
        val row = findSegment(id) ?: return@transaction false
        if (row.status !in setOf("Failed", "Quiet", "Abandoned")) return@transaction false
        val file = java.io.File(row.path)
        if (file.exists() && !file.delete()) return@transaction false
        deleteSamples(id)
        deleteExclusions(id)
        deleteAttempts(id)
        deleteLineItems(id)
        deleteExclusiveEvents(id)
        deleteSegmentRow(id) // 原话由外键级联删除；累计用量不回退。
        true
    }

suspend fun TranscribeDao.retryFailed() = retryFailed(System.currentTimeMillis())
suspend fun TranscribeDao.ignoreFailed() = ignoreFailed(System.currentTimeMillis())

suspend fun TranscribeDao.completeRedo(row: SegmentEntity, fresh: List<TranscriptLineEntity>, at: Long) = transaction {
        val old = linesForRedo(row.id)
        data class Candidate(val freshIndex: Int, val oldIndex: Int, val exact: Boolean, val overlap: Long)
        val candidates = buildList {
            fresh.forEachIndexed { fi, line ->
                old.forEachIndexed { oi, previous ->
                    val exact = previous.startAt == line.startAt && previous.endAt == line.endAt
                    val overlap = minOf(previous.endAt, line.endAt) - maxOf(previous.startAt, line.startAt)
                    if (exact || overlap > 0) add(Candidate(fi, oi, exact, overlap))
                }
            }
        }.sortedWith(compareByDescending<Candidate> { it.exact }.thenByDescending { it.overlap }
            .thenBy { it.freshIndex }.thenBy { it.oldIndex })
        val matches = arrayOfNulls<Int>(fresh.size)
        val used = BooleanArray(old.size)
        for ((fi, oi) in candidates) {
            if (matches[fi] == null && !used[oi]) {
                matches[fi] = oi
                used[oi] = true
            }
        }
        fresh.indices.filter { matches[it] == null }.zip(old.indices.filterNot { used[it] }).forEach { (fi, oi) ->
            matches[fi] = oi
        }
        val mapped = fresh.mapIndexed { fi, line ->
            val match = matches[fi]?.let { old[it] }
            val mappedLine = line.copy(id = match?.id ?: 0, eventId = match?.eventId, digestedAt = match?.digestedAt ?: at)
            if (match == null || !match.speakerManual) return@mapIndexed mappedLine
            val ov = minOf(match.endAt, line.endAt) - maxOf(match.startAt, line.startAt)
            val exact = match.startAt == line.startAt && match.endAt == line.endAt
            val substantial = ov > 0 && ov * 2 >= maxOf(match.endAt - match.startAt, line.endAt - line.startAt)
            if (exact || substantial) mappedLine.copy(speakerManual = true, speakerName = match.speakerName,
                speakerPersonId = match.speakerPersonId, speakerScore = match.speakerScore) else mappedLine
        }
        complete(row.copy(redoPending = false), mapped)
        val saved = linesForRedo(row.id)
        for (line in old) if (saved.none { it.id == line.id }) {
            val replacement = saved.maxByOrNull { (minOf(it.endAt, line.endAt) - maxOf(it.startAt, line.startAt)).coerceAtLeast(0) }?.id
            remapItemLine(line.id, replacement); remapHintLine(line.id, replacement)
        }
    }

suspend fun TranscribeDao.complete(row: SegmentEntity, lines: List<TranscriptLineEntity>) = transaction {
        if (findSegment(row.id) == null) return@transaction
        deleteLines(row.id)
        // 语音输入那条已经记下的话（带上下文），录音转写里不再重复记；重转时同样过一遍
        val voice = if (lines.isEmpty()) emptyList() else voiceInputs(lines.minOf { it.startAt } - VoiceInputDedup.MIC_AFTER_MS,
            lines.maxOf { it.startAt } + VoiceInputDedup.MIC_BEFORE_MS)
        insertLines(lines.filter { line -> voice.none { VoiceInputDedup.heardByMic(it.startAt, it.text, line.startAt, line.text, line.speakerName) } })
        update(row)
    }
