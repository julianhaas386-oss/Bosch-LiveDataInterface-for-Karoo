package de.dxmedia.bosch.ldi.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BikeProfile(
    val id: String,
    val name: String,
    val bleAddress: String,
    val isActive: Boolean,
    val enabledFields: Set<String> = DEFAULT_FIELDS
) {
    companion object {
        val DEFAULT_FIELDS: Set<String> = setOf(
            "bosch_ldi_speed",
            "bosch_ldi_cadence",
            "bosch_ldi_rider_power",
            "bosch_ldi_battery_soc",
            "bosch_ldi_odometer",
            "bosch_ldi_time",
            "bosch_ldi_bike_light",
            "bosch_ldi_ambient_brightness",
            "bosch_ldi_light_reserve_state",
            "bosch_ldi_system_locked",
            "bosch_ldi_charger_connected",
            "bosch_ldi_diagnosis_program_active",
            "bosch_ldi_bike_not_driving",
            "bosch_ldi_connection",
            "bosch_ldi_ebike_dashboard"
        )

        private val NAME_REGEX = Regex("""^[a-zA-Z0-9 _\-]{1,64}$""")
        private val BLE_ADDRESS_REGEX = Regex("""^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$""")

        fun create(name: String, bleAddress: String): BikeProfile = BikeProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            bleAddress = bleAddress,
            isActive = false
        )

        fun isValidName(name: String): Boolean =
            name.isNotBlank() && NAME_REGEX.matches(name)

        fun isValidBleAddress(address: String): Boolean =
            BLE_ADDRESS_REGEX.matches(address)

        fun serialize(profiles: List<BikeProfile>): String {
            val arr = JSONArray()
            profiles.forEach { p ->
                val fields = JSONArray()
                p.enabledFields.sorted().forEach { fields.put(it) }
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("bleAddress", p.bleAddress)
                    put("isActive", p.isActive)
                    put("enabledFields", fields)
                })
            }
            return arr.toString()
        }

        fun deserialize(json: String): List<BikeProfile> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val fieldsArr = obj.getJSONArray("enabledFields")
                BikeProfile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    bleAddress = obj.getString("bleAddress"),
                    isActive = obj.getBoolean("isActive"),
                    enabledFields = (0 until fieldsArr.length())
                        .map { fieldsArr.getString(it) }.toSet()
                )
            }
        }
    }
}
