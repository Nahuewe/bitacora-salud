package ar.nahuel.bitacora.domain

import java.time.DayOfWeek
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class InvalidProgramException(message: String) : IllegalArgumentException(message)

@Serializable
private data class ProgramDto(
    val version: Int,
    val warmUp: List<String>,
    val effortRule: String,
    val phases: List<PhaseDto>,
    val routines: List<RoutineDto>,
    val week: List<DayDto>,
    val weekChanges: List<WeekChangeDto> = emptyList(),
    val exercises: List<ExerciseDto>,
)

@Serializable
private data class PhaseDto(val name: String, val weeks: String, val rule: String, val untilWeek: Int? = null)

@Serializable
private data class RoutineDto(
    val id: String,
    val name: String,
    val minutes: Int,
    val exercises: List<String>,
    val format: String = "",
    val purpose: String = "",
    val strength: Boolean = false,
)

@Serializable
private data class DayDto(val day: String, val kind: String, val title: String, val detail: String, val routines: List<String> = emptyList())

@Serializable
private data class WeekChangeDto(val fromWeek: Int, val replace: DayDto)

@Serializable
private data class ExerciseDto(
    val id: String,
    val name: String,
    val prescription: String,
    val rest: String,
    val equipment: String,
    val cues: List<String>,
    val avoid: List<String>,
    val easier: String,
    val harder: String,
    val frames: List<FrameDto>,
)

@Serializable
private data class FrameDto(
    val label: String,
    val pose: PoseDto,
    val props: List<PropDto> = emptyList(),
    val guides: List<LineDto> = emptyList(),
    val dumbbell: Boolean = false,
    val wrong: Boolean = false,
)

@Serializable
private data class PoseDto(
    val head: List<Double>,
    val shoulder: List<Double>,
    val elbow: List<Double>,
    val hand: List<Double>,
    val hip: List<Double>,
    val knee: List<Double>,
    val ankle: List<Double>,
    val elbow2: List<Double>? = null,
    val hand2: List<Double>? = null,
    val knee2: List<Double>? = null,
    val ankle2: List<Double>? = null,
    val feet: Boolean = true,
    val footTo: List<Double>? = null,
)

@Serializable
private data class PropDto(
    val type: String,
    val left: Double? = null,
    val top: Double? = null,
    val right: Double? = null,
    val bottom: Double? = null,
    val from: List<Double>? = null,
    val to: List<Double>? = null,
)

@Serializable
private data class LineDto(val from: List<Double>, val to: List<Double>)

object StrengthProgramParser {

    const val SUPPORTED_VERSION = 1
    private const val MAX_TEXT = 600
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(source: String): StrengthProgram {
        val dto = try {
            json.decodeFromString(ProgramDto.serializer(), source)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw InvalidProgramException("formato inválido: ${e.message?.take(120)}")
        }
        check(dto.version == SUPPORTED_VERSION) { "versión ${dto.version} no soportada" }

        val exercises = dto.exercises.associate { it.id to exercise(it) }
        check(exercises.size == dto.exercises.size) { "ids de ejercicio repetidos" }

        val routines = dto.routines.map { r ->
            check(r.id.matches(ID_PATTERN)) { "id de rutina inválido: ${r.id}" }
            check(r.minutes in 1..240) { "duración inválida en ${r.id}" }
            check(r.exercises.isNotEmpty()) { "la rutina ${r.id} no tiene ejercicios" }
            Routine(
                id = r.id,
                name = clean(r.name),
                minutes = r.minutes,
                exercises = r.exercises.map { id -> exercises[id] ?: fail("la rutina ${r.id} usa un ejercicio inexistente: $id") },
                format = clean(r.format),
                purpose = clean(r.purpose),
                strength = r.strength,
            )
        }
        check(routines.map { it.id }.toSet().size == routines.size) { "ids de rutina repetidos" }
        val routineIds = routines.map { it.id }.toSet()

        fun day(d: DayDto): PlanDay {
            val dayOfWeek = enumOrNull<DayOfWeek>(d.day) ?: fail("día inválido: ${d.day}")
            val kind = enumOrNull<DayKind>(d.kind) ?: fail("tipo de día inválido: ${d.kind}")
            d.routines.forEach { check(it in routineIds) { "el plan usa una rutina inexistente: $it" } }
            return PlanDay(dayOfWeek, kind, clean(d.title), clean(d.detail), d.routines)
        }
        val week = dto.week.map(::day)
        check(week.map { it.day }.toSet() == DayOfWeek.entries.toSet() && week.size == 7) { "el plan semanal debe tener los 7 días una vez" }
        val changes = dto.weekChanges.map { change ->
            check(change.fromWeek in 1..520) { "semana inválida: ${change.fromWeek}" }
            change.fromWeek to day(change.replace)
        }

        val phases = dto.phases.map { Phase(clean(it.name), clean(it.weeks), clean(it.rule), it.untilWeek) }
        check(phases.isNotEmpty() && phases.last().untilWeek == null) { "la última fase debe ser abierta" }
        val limits = phases.mapNotNull { it.untilWeek }
        check(limits == limits.sorted() && limits.toSet().size == limits.size) { "las fases deben ir en orden" }

        return StrengthProgram(
            warmUp = dto.warmUp.map(::clean),
            effortRule = clean(dto.effortRule),
            phases = phases,
            routines = routines,
            week = week.sortedBy { it.day.value },
            weekChanges = changes,
        )
    }

    private fun exercise(e: ExerciseDto): Exercise {
        check(e.id.matches(ID_PATTERN)) { "id de ejercicio inválido: ${e.id}" }
        check(e.frames.isNotEmpty()) { "el ejercicio ${e.id} no tiene posturas" }
        return Exercise(
            id = e.id,
            name = clean(e.name),
            prescription = clean(e.prescription),
            rest = clean(e.rest),
            equipment = clean(e.equipment),
            cues = e.cues.map(::clean),
            avoid = e.avoid.map(::clean),
            easier = clean(e.easier),
            harder = clean(e.harder),
            frames = e.frames.map { frame(e.id, it) },
        )
    }

    private fun frame(owner: String, f: FrameDto): Frame {
        fun pt(v: List<Double>): P {
            check(v.size == 2 && v.all { it in 0.0..1.0 }) { "coordenada fuera de rango en $owner" }
            return P(v[0].toFloat(), v[1].toFloat())
        }
        fun ptOrNull(v: List<Double>?) = v?.let(::pt)
        val pose = f.pose
        return Frame(
            label = clean(f.label),
            pose = Pose(
                head = pt(pose.head),
                shoulder = pt(pose.shoulder),
                elbow = pt(pose.elbow),
                hand = pt(pose.hand),
                hip = pt(pose.hip),
                knee = pt(pose.knee),
                ankle = pt(pose.ankle),
                elbow2 = ptOrNull(pose.elbow2),
                hand2 = ptOrNull(pose.hand2),
                knee2 = ptOrNull(pose.knee2),
                ankle2 = ptOrNull(pose.ankle2),
                feet = pose.feet,
                footTo = ptOrNull(pose.footTo),
            ),
            props = f.props.map { prop ->
                when (prop.type) {
                    "block" -> {
                        val edges = listOf(prop.left, prop.top, prop.right, prop.bottom)
                        check(edges.all { it != null && it in 0.0..1.0 }) { "bloque inválido en $owner" }
                        Prop.Block(prop.left!!.toFloat(), prop.top!!.toFloat(), prop.right!!.toFloat(), prop.bottom!!.toFloat())
                    }
                    "band" -> Prop.Band(pt(prop.from ?: fail("banda sin origen en $owner")), pt(prop.to ?: fail("banda sin destino en $owner")))
                    else -> fail("elemento desconocido en $owner: ${prop.type}")
                }
            },
            guides = f.guides.map { Guide(pt(it.from), pt(it.to)) },
            dumbbell = f.dumbbell,
            wrong = f.wrong,
        )
    }

    private fun clean(value: String): String {
        check(value.length <= MAX_TEXT) { "texto demasiado largo" }
        check(value.none { it.isISOControl() }) { "texto con caracteres de control" }
        return value
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

    private fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw InvalidProgramException(message())
    }

    private fun fail(message: String): Nothing = throw InvalidProgramException(message)

    private val ID_PATTERN = Regex("[A-Za-z0-9-]{1,40}")
}
