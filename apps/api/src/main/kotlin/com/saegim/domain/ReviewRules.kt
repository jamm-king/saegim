package com.saegim.domain

object ReviewRules {
    fun checkBudget(source: List<Message>) {
        if (source.sumOf { it.content.length.toLong() } > 60000) throw AiUnavailable("선택한 대화가 복습 입력 한도 60,000자를 넘었습니다. 날짜별 전체 원문을 포함하며 일부를 잘라서 출제하지 않았습니다.")
    }
    fun validate(questions: List<GeneratedQuestion>, source: List<Message>, reviewedIds: Set<Long> = emptySet()) {
        if (questions.any { question -> question.sourceIds.any { it in reviewedIds } })
            throw AiUnavailable("이미 답한 질문의 근거로 다시 출제되었습니다. 다시 생성해 주세요.")
        val ids = source.map { it.id }.toSet()
        val assistantIds = source.filter { it.role == "assistant" }.map { it.id }.toSet()
        if (questions.size > 3 || questions.map { it.question.trim() }.distinct().size != questions.size || questions.any {
                it.question.isBlank() || it.question.length > 1500 || it.expectedAnswer.isBlank() || it.expectedAnswer.length > 3000 ||
                it.sourceIds.isEmpty() || it.sourceIds.size > 8 || it.sourceIds.any { id -> id !in ids } || it.sourceIds.none { id -> id in assistantIds }
            }) throw AiUnavailable("복습 질문의 형식 또는 원문 근거가 올바르지 않습니다. 다시 생성해 주세요.")
    }
}
