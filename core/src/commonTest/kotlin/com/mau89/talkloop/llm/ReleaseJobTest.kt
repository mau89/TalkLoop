package com.mau89.talkloop.llm

import kotlin.test.*

class ReleaseJobTest {
    @Test fun releaseVersionAndCommitInputsAreBounded() {
        ReleaseRequest("HEAD~2", "1.1.0-rc.1", 2).validate()
        ReleaseRequest("🔥-День-34", "1.1.0", 2).validate()
        for (request in listOf(ReleaseRequest("", "1.1.0", 2), ReleaseRequest("--help", "1.1.0", 2),
            ReleaseRequest("HEAD\nmain", "1.1.0", 2), ReleaseRequest("HEAD", "1.1", 2),
            ReleaseRequest("HEAD", "01.1.0", 2), ReleaseRequest("HEAD", "1.1.0", 0))) {
            assertFailsWith<IllegalArgumentException> { request.validate() }
        }
    }
}
