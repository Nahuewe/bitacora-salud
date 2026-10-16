package ar.nahuel.bitacora.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class P(val x: Float, val y: Float)

data class Pose(
    val head: P,
    val shoulder: P,
    val elbow: P,
    val hand: P,
    val hip: P,
    val knee: P,
    val ankle: P,
    val elbow2: P? = null,
    val hand2: P? = null,
    val knee2: P? = null,
    val ankle2: P? = null,
    val feet: Boolean = true,
)

sealed interface Prop {
    data class Block(val left: Float, val top: Float, val right: Float, val bottom: Float) : Prop
    data class Band(val from: P, val to: P) : Prop
}

data class Guide(val from: P, val to: P)

data class Frame(
    val label: String,
    val pose: Pose,
    val props: List<Prop> = emptyList(),
    val guides: List<Guide> = emptyList(),
    val dumbbell: Boolean = false,
    val wrong: Boolean = false,
)

data class Exercise(
    val id: String,
    val name: String,
    val prescription: String,
    val rest: String,
    val equipment: String,
    val cues: List<String>,
    val avoid: List<String>,
    val easier: String,
    val harder: String,
    val frames: List<Frame>,
)

data class Routine(val id: String, val name: String, val minutes: Int, val exercises: List<Exercise>)

enum class DayKind { STRENGTH, EASY_WALK, INTERVALS, LONG_WALK, REST }

data class PlanDay(val day: DayOfWeek, val kind: DayKind, val title: String, val detail: String, val routineId: String? = null)

data class Phase(val name: String, val weeks: String, val rule: String)

object StrengthProgram {

    private fun p(x: Double, y: Double) = P(x.toFloat(), y.toFloat())

    val warmUp = listOf(
        "2 minutos caminando en el lugar o subiendo y bajando un escalón.",
        "10 sentadillas a banco sin peso, lentas.",
        "10 círculos de hombros hacia atrás y 10 hacia adelante.",
        "8 bisagras de cadera con un palo apoyado en la espalda: cabeza, espalda alta y cola tocan el palo todo el tiempo.",
    )

    private val boxSquat = Exercise(
        id = "box-squat",
        name = "Sentadilla a banco",
        prescription = "3 × 8–10",
        rest = "90 s",
        equipment = "Banco o silla firme. Más adelante, una mancuerna sostenida al pecho.",
        cues = listOf(
            "Pies al ancho de hombros, puntas apenas hacia afuera.",
            "Llevá la cadera hacia atrás como buscando la silla; las rodillas siguen la dirección de las puntas.",
            "Tocá el banco sin desplomarte y subí empujando el piso con todo el pie.",
            "Pecho alto y mirada al frente durante todo el movimiento.",
        ),
        avoid = listOf(
            "Rodillas que se juntan hacia adentro al subir.",
            "Talones que se despegan del piso.",
            "Caer de golpe sobre el banco.",
        ),
        easier = "Banco más alto o almohadón encima.",
        harder = "Mancuerna al pecho (sentadilla goblet) o banco más bajo.",
        frames = listOf(
            Frame(
                "Inicio",
                Pose(p(.5, .12), p(.5, .24), p(.6, .3), p(.72, .3), p(.5, .52), p(.51, .72), p(.5, .92)),
                props = listOf(Prop.Block(.2f, .69f, .4f, .92f)),
            ),
            Frame(
                "Abajo",
                Pose(p(.56, .31), p(.51, .42), p(.63, .46), p(.75, .46), p(.39, .67), p(.58, .73), p(.52, .92)),
                props = listOf(Prop.Block(.2f, .69f, .4f, .92f)),
                guides = listOf(Guide(p(.58, .73), p(.58, .92))),
            ),
        ),
    )

    private val dumbbellRow = Exercise(
        id = "row",
        name = "Remo con mancuerna apoyado",
        prescription = "3 × 10 por lado",
        rest = "60–90 s",
        equipment = "Una mancuerna y un banco, silla o mesa para apoyar la otra mano.",
        cues = listOf(
            "Una mano en el banco y los pies separados; espalda plana, casi paralela al piso.",
            "Llevá el codo hacia la cadera, rozando el costado del cuerpo.",
            "Arriba, juntá el omóplato hacia la columna un segundo.",
            "Bajá lento hasta estirar el brazo por completo.",
        ),
        avoid = listOf(
            "Girar el torso para subir el peso.",
            "Encoger el hombro hacia la oreja.",
            "Redondear la espalda baja.",
        ),
        easier = "Banda elástica en lugar de mancuerna.",
        harder = "Más peso o una pausa de un segundo arriba.",
        frames = listOf(
            Frame(
                "Inicio",
                Pose(p(.73, .57), p(.63, .6), p(.62, .7), p(.62, .8), p(.36, .54), p(.4, .73), p(.38, .92), elbow2 = p(.66, .68), hand2 = p(.68, .76)),
                props = listOf(Prop.Block(.6f, .77f, .9f, .92f)),
                guides = listOf(Guide(p(.3, .53), p(.7, .62))),
                dumbbell = true,
            ),
            Frame(
                "Final",
                Pose(p(.73, .57), p(.63, .6), p(.5, .52), p(.55, .62), p(.36, .54), p(.4, .73), p(.38, .92), elbow2 = p(.66, .68), hand2 = p(.68, .76)),
                props = listOf(Prop.Block(.6f, .77f, .9f, .92f)),
                guides = listOf(Guide(p(.3, .53), p(.7, .62))),
                dumbbell = true,
            ),
        ),
    )

    private val inclinePushUp = Exercise(
        id = "incline-push-up",
        name = "Flexiones inclinadas",
        prescription = "3 × 8–12",
        rest = "90 s",
        equipment = "Mesa firme, mesada o la pared.",
        cues = listOf(
            "Manos un poco más anchas que los hombros, a la altura del pecho.",
            "Cuerpo en línea recta de la cabeza a los talones: apretá glúteos y abdomen.",
            "Codos a unos 45° del cuerpo, no abiertos en cruz.",
            "Bajá hasta que el pecho casi toque el borde y empujá lejos.",
        ),
        avoid = listOf(
            "Cadera hundida o levantada.",
            "Cabeza adelantada buscando el borde.",
        ),
        easier = "Contra la pared.",
        harder = "Superficie más baja, o press con mancuernas en un banco.",
        frames = listOf(
            Frame(
                "Arriba",
                Pose(p(.73, .38), p(.66, .43), p(.69, .52), p(.72, .62), p(.42, .65), p(.27, .78), p(.12, .92), feet = false),
                props = listOf(Prop.Block(.66f, .62f, .95f, .92f)),
                guides = listOf(Guide(p(.73, .38), p(.12, .92))),
            ),
            Frame(
                "Abajo",
                Pose(p(.75, .5), p(.68, .54), p(.57, .6), p(.72, .62), p(.43, .71), p(.28, .81), p(.12, .92), feet = false),
                props = listOf(Prop.Block(.66f, .62f, .95f, .92f)),
                guides = listOf(Guide(p(.75, .5), p(.12, .92))),
            ),
        ),
    )

    private val gluteBridge = Exercise(
        id = "glute-bridge",
        name = "Puente de glúteo",
        prescription = "3 × 12",
        rest = "60 s",
        equipment = "Una colchoneta o una toalla.",
        cues = listOf(
            "Boca arriba, rodillas dobladas, pies apoyados al ancho de la cadera.",
            "Empujá con los talones y subí la cadera hasta alinear hombros, cadera y rodillas.",
            "Apretá los glúteos dos segundos arriba.",
            "Bajá controlado, sin dejarte caer.",
        ),
        avoid = listOf(
            "Arquear la zona lumbar para subir más.",
            "Empujar con las puntas de los pies.",
        ),
        easier = "Subir menos, solo hasta donde sientas el glúteo.",
        harder = "Una pierna a la vez, o una mancuerna sobre la cadera.",
        frames = listOf(
            Frame(
                "Abajo",
                Pose(p(.14, .86), p(.24, .88), p(.34, .9), p(.44, .9), p(.5, .88), p(.64, .7), p(.72, .9)),
            ),
            Frame(
                "Arriba",
                Pose(p(.14, .86), p(.24, .88), p(.34, .9), p(.44, .9), p(.5, .7), p(.66, .64), p(.72, .9)),
                guides = listOf(Guide(p(.24, .88), p(.66, .64))),
            ),
        ),
    )

    private val plank = Exercise(
        id = "plank",
        name = "Plancha",
        prescription = "3 × 20–30 s",
        rest = "60 s",
        equipment = "Una colchoneta.",
        cues = listOf(
            "Antebrazos en el piso, codos justo debajo de los hombros.",
            "Línea recta de la cabeza a los talones.",
            "Apretá glúteos y abdomen como si fueras a recibir un golpe.",
            "Respirá normal durante todo el tiempo.",
        ),
        avoid = listOf(
            "Cadera hundida: carga la zona lumbar.",
            "Cadera muy alta: le quita trabajo al abdomen.",
            "Aguantar la respiración.",
        ),
        easier = "Con las rodillas apoyadas.",
        harder = "Sumar 10 segundos por semana hasta llegar a 45.",
        frames = listOf(
            Frame(
                "Bien",
                Pose(p(.16, .69), p(.26, .72), p(.26, .9), p(.12, .9), p(.55, .8), p(.7, .84), p(.86, .88), feet = false),
                guides = listOf(Guide(p(.16, .69), p(.86, .88))),
            ),
            Frame(
                "Evitá",
                Pose(p(.16, .69), p(.26, .72), p(.26, .9), p(.12, .9), p(.55, .88), p(.7, .88), p(.86, .88), feet = false),
                guides = listOf(Guide(p(.16, .69), p(.86, .88))),
                wrong = true,
            ),
        ),
    )

    private val romanianDeadlift = Exercise(
        id = "rdl",
        name = "Peso muerto rumano",
        prescription = "3 × 8–10",
        rest = "90 s",
        equipment = "Dos mancuernas, o un palo para aprender el movimiento.",
        cues = listOf(
            "De pie, una mancuerna en cada mano frente a los muslos.",
            "Rodillas apenas flexionadas y fijas durante todo el movimiento.",
            "Llevá la cadera hacia atrás como si cerraras una puerta con la cola.",
            "Las mancuernas bajan pegadas a las piernas hasta media tibia o hasta donde la espalda siga recta.",
            "Subí apretando los glúteos.",
        ),
        avoid = listOf(
            "Redondear la espalda.",
            "Doblar las rodillas como en una sentadilla.",
            "Levantar la cabeza para mirar adelante.",
        ),
        easier = "Practicar la bisagra con un palo en la espalda.",
        harder = "Más peso o bajada de tres segundos.",
        frames = listOf(
            Frame(
                "Inicio",
                Pose(p(.5, .12), p(.5, .24), p(.5, .38), p(.52, .52), p(.5, .52), p(.51, .72), p(.5, .92)),
                dumbbell = true,
            ),
            Frame(
                "Abajo",
                Pose(p(.63, .62), p(.54, .64), p(.53, .73), p(.51, .82), p(.32, .5), p(.42, .71), p(.46, .92)),
                guides = listOf(Guide(p(.26, .46), p(.62, .69))),
                dumbbell = true,
            ),
        ),
    )

    private val shoulderPress = Exercise(
        id = "shoulder-press",
        name = "Press de hombros sentado",
        prescription = "3 × 8–10",
        rest = "90 s",
        equipment = "Dos mancuernas y una silla con respaldo.",
        cues = listOf(
            "Sentado con la espalda apoyada y los pies firmes en el piso.",
            "Mancuernas a la altura de los hombros, palmas al frente o enfrentadas.",
            "Empujá hacia arriba sin trabar los codos de golpe.",
            "Costillas abajo y abdomen firme para no arquear la espalda.",
        ),
        avoid = listOf(
            "Arquear la espalda para empujar.",
            "Codos muy por detrás del cuerpo.",
        ),
        easier = "Un brazo a la vez, o menos peso.",
        harder = "De pie, con glúteos y abdomen apretados.",
        frames = listOf(
            Frame(
                "Inicio",
                Pose(p(.43, .24), p(.42, .36), p(.52, .44), p(.52, .33), p(.42, .64), p(.62, .64), p(.64, .92)),
                props = listOf(Prop.Block(.3f, .66f, .62f, .92f), Prop.Block(.3f, .3f, .35f, .66f)),
                dumbbell = true,
            ),
            Frame(
                "Arriba",
                Pose(p(.43, .24), p(.42, .36), p(.47, .22), p(.46, .1), p(.42, .64), p(.62, .64), p(.64, .92)),
                props = listOf(Prop.Block(.3f, .66f, .62f, .92f), Prop.Block(.3f, .3f, .35f, .66f)),
                dumbbell = true,
            ),
        ),
    )

    private val splitSquat = Exercise(
        id = "split-squat",
        name = "Sentadilla dividida con apoyo",
        prescription = "3 × 8 por pierna",
        rest = "90 s",
        equipment = "Una pared o silla para apoyar una mano.",
        cues = listOf(
            "Un pie adelante y otro atrás, separados por un paso largo.",
            "Bajá en vertical: la rodilla de atrás va hacia el piso.",
            "El peso va en el talón de adelante, con la rodilla sobre el tobillo.",
            "Torso erguido durante todo el movimiento.",
        ),
        avoid = listOf(
            "Rodilla de adelante que cae hacia adentro.",
            "Pasos cortos: cargan la rodilla.",
        ),
        easier = "Bajar menos.",
        harder = "Una mancuerna en cada mano.",
        frames = listOf(
            Frame(
                "Arriba",
                Pose(p(.5, .12), p(.5, .24), p(.5, .38), p(.51, .5), p(.5, .52), p(.6, .7), p(.66, .92), knee2 = p(.42, .72), ankle2 = p(.32, .9)),
            ),
            Frame(
                "Abajo",
                Pose(p(.5, .24), p(.5, .36), p(.5, .5), p(.51, .62), p(.5, .64), p(.66, .7), p(.66, .92), knee2 = p(.44, .88), ankle2 = p(.3, .9)),
                guides = listOf(Guide(p(.66, .7), p(.66, .92))),
            ),
        ),
    )

    private val bandRow = Exercise(
        id = "band-row",
        name = "Remo con banda elástica",
        prescription = "3 × 12",
        rest = "60 s",
        equipment = "Una banda elástica anclada a la altura del pecho (puerta cerrada o poste).",
        cues = listOf(
            "De pie, rodillas sueltas, banda tensa con los brazos estirados.",
            "Tirá llevando los codos hacia atrás y juntando los omóplatos.",
            "Hombros lejos de las orejas.",
            "Volvé lento, sin que la banda te arrastre.",
        ),
        avoid = listOf(
            "Tirar echando la espalda hacia atrás.",
            "Encoger los hombros.",
        ),
        easier = "Un paso más cerca del anclaje.",
        harder = "Banda más dura o un paso más lejos.",
        frames = listOf(
            Frame(
                "Inicio",
                Pose(p(.4, .12), p(.4, .24), p(.52, .34), p(.64, .36), p(.4, .52), p(.42, .72), p(.4, .92)),
                props = listOf(Prop.Band(p(.64, .36), p(.95, .36)), Prop.Block(.95f, .2f, .98f, .92f)),
            ),
            Frame(
                "Final",
                Pose(p(.4, .12), p(.4, .24), p(.3, .38), p(.42, .38), p(.4, .52), p(.42, .72), p(.4, .92)),
                props = listOf(Prop.Band(p(.42, .38), p(.95, .36)), Prop.Block(.95f, .2f, .98f, .92f)),
            ),
        ),
    )

    private val deadBug = Exercise(
        id = "dead-bug",
        name = "Dead bug",
        prescription = "3 × 8 por lado",
        rest = "60 s",
        equipment = "Una colchoneta.",
        cues = listOf(
            "Boca arriba, brazos hacia el techo y rodillas a 90° sobre la cadera.",
            "Pegá la zona lumbar al piso y mantenela así.",
            "Estirá un brazo hacia atrás y la pierna contraria hacia adelante, despacio.",
            "Volvé al centro y cambiá de lado.",
        ),
        avoid = listOf(
            "Que se despegue la zona lumbar.",
            "Apurarse: el ejercicio está en el control.",
        ),
        easier = "Mover solo las piernas.",
        harder = "Bajada de tres segundos en cada lado.",
        frames = listOf(
            Frame(
                "Inicio",
                Pose(p(.12, .86), p(.22, .86), p(.22, .72), p(.22, .6), p(.48, .86), p(.48, .66), p(.64, .66), feet = false),
            ),
            Frame(
                "Extensión",
                Pose(
                    p(.12, .86), p(.22, .86), p(.12, .8), p(.03, .82), p(.48, .86), p(.66, .8), p(.84, .84),
                    elbow2 = p(.22, .72), hand2 = p(.22, .6), knee2 = p(.48, .66), ankle2 = p(.64, .66), feet = false,
                ),
            ),
        ),
    )

    val routines = listOf(
        Routine("A", "Fuerza A", 35, listOf(boxSquat, dumbbellRow, inclinePushUp, gluteBridge, plank)),
        Routine("B", "Fuerza B", 35, listOf(romanianDeadlift, shoulderPress, splitSquat, bandRow, deadBug)),
    )

    fun routine(id: String): Routine? = routines.firstOrNull { it.id == id }

    val phases = listOf(
        Phase("Técnica", "Semanas 1–2", "Peso liviano. Terminá cada serie sintiendo que podrías hacer 4 repeticiones más. El objetivo es aprender el movimiento."),
        Phase("Progresión", "Semanas 3–6", "Cuando completes todas las series en el tope del rango con buena técnica (por ejemplo 3 × 10), subí 1 a 2 kg en la sesión siguiente."),
        Phase("Volumen", "Semana 7 en adelante", "Sumá el sábado como tercer día de fuerza y pasá a 4 series en la sentadilla y el peso muerto rumano."),
    )

    const val EFFORT_RULE = "Terminá cada serie con 2 o 3 repeticiones en reserva. La molestia muscular al día siguiente es normal; un dolor articular punzante, o que empeora durante el ejercicio, no: pará y pasá a la versión más fácil."

    fun weekNumber(start: LocalDate, today: LocalDate): Int =
        (ChronoUnit.DAYS.between(start, today).coerceAtLeast(0) / 7 + 1).toInt()

    fun phaseIndex(week: Int): Int = when {
        week <= 2 -> 0
        week <= 6 -> 1
        else -> 2
    }

    fun weekPlan(week: Int): List<PlanDay> = listOf(
        PlanDay(DayOfWeek.MONDAY, DayKind.STRENGTH, "Fuerza A", "35 min. Caminata suave opcional.", "A"),
        PlanDay(DayOfWeek.TUESDAY, DayKind.EASY_WALK, "Caminata suave", "40 a 60 min a ritmo de conversación."),
        PlanDay(DayOfWeek.WEDNESDAY, DayKind.INTERVALS, "Caminata con intervalos", "5 o 6 bloques de 3 min rápido o en subida y 2 min suave."),
        PlanDay(DayOfWeek.THURSDAY, DayKind.STRENGTH, "Fuerza B", "35 min.", "B"),
        PlanDay(DayOfWeek.FRIDAY, DayKind.EASY_WALK, "Caminata suave", "40 a 60 min a ritmo de conversación."),
        if (week >= 7) {
            PlanDay(DayOfWeek.SATURDAY, DayKind.STRENGTH, "Fuerza A + caminata", "Tercer día de fuerza y caminata cómoda.", "A")
        } else {
            PlanDay(DayOfWeek.SATURDAY, DayKind.LONG_WALK, "Caminata larga", "60 a 90 min a ritmo cómodo.")
        },
        PlanDay(DayOfWeek.SUNDAY, DayKind.REST, "Descanso activo", "20 a 30 min de caminata tranquila y 10 min de movilidad."),
    )
}
