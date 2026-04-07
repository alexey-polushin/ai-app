package me.lekseg.aiapp.mlcext

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

internal data class AppConfig(
    @SerializedName("model_libs") var modelLibs: MutableList<String>? = null,
    @SerializedName("model_list") val modelList: MutableList<ModelRecord>? = null,
)

internal data class ModelRecord(
    @SerializedName(value = "model_url", alternate = ["model"])
    val modelUrl: String,
    @SerializedName("model_id") val modelId: String,
    @SerializedName("estimated_vram_bytes") val estimatedVramBytes: Long?,
    @SerializedName("model_lib") val modelLib: String,
    @SerializedName("overrides") val overrides: JsonObject? = null,
)

internal data class ParamsRecord(
    @SerializedName("dataPath") val dataPath: String,
)

internal data class ParamsConfig(
    @SerializedName("records") val paramsRecords: List<ParamsRecord> = emptyList(),
)
