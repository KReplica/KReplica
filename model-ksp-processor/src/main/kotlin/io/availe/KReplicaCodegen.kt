package io.availe

import io.availe.models.Model
import io.availe.validation.validateModelReplications

internal object KReplicaCodegen {
    fun validate(
        allModels: List<Model>
    ) {
        if (allModels.isEmpty()) return
        validateModelReplications(allModels)
    }
}