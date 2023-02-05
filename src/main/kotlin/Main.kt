import com.fazecast.jSerialComm.SerialPort
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString
import kotlin.streams.toList
import kotlin.system.exitProcess

fun InputStream.readUpToChar(stopChar: Char): String {
    val stringBuilder = StringBuilder()
    var currentChar = this.read().toChar()

    while (currentChar != stopChar) {
        stringBuilder.append(currentChar)
        currentChar = this.read().toChar()
    }
    return stringBuilder.toString()
}

class AppState(private val logger: Logger) {
    var activePlayerProcess: Process? = null
    val songs = arrayListOf<String>()
    var selectedSongIdx: Int = 0
    var selectedFrequency: String = "87.6"

    fun isPlaying(): Boolean {
        return activePlayerProcess?.isAlive == true
    }

    val selectedSong: String
        get() {
            return songs[selectedSongIdx]
        }

    fun loadSongs(): List<String> {
        val wd = Path(".").toAbsolutePath().normalize()
        val s = Files.list(wd).filter {
            it.extension == "wav"
        }.toList()

        songs.clear()
        songs.addAll(s.map { it.pathString })

        logger.info("Loaded songs: \n${songs.joinToString("\n")}")

        return s.map { it.nameWithoutExtension }.toList()
    }
}

fun main() {
    val logger: Logger = LoggerFactory.getLogger("PiFM-F0")

    val state = AppState(logger)

    state.loadSongs()
    logger.info("Finished loading songs")

    val sp = SerialPort.getCommPort("/dev/ttyS0")
    sp.baudRate = 115200
    sp.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)

    sp.openPort()

    logger.info("Listening for messages on /dev/ttyS0")

    while (true) {
        val content = sp.inputStream.use {
            it.readUpToChar('\n')
        }

        logger.info("Received command $content")

        when (content) {
            "exit" -> {
                logger.info("Exiting")
                exitProcess(0)
            }

            "play" -> {
                if (!state.isPlaying() && state.songs.size > state.selectedSongIdx) {
                    val proc = ProcessBuilder(
                        "./pi_fm_rds", "-freq", state.selectedFrequency, "-audio",
                        state.selectedSong
                    )
                        .inheritIO()
                        .start()
                    state.activePlayerProcess = proc
                    logger.info("Playing ${state.selectedSong}")
                }
            }

            "stop" -> {
                if (state.isPlaying()) {
                    state.activePlayerProcess?.destroy()
                    logger.info("Stopped")
                }
                state.activePlayerProcess = null
            }

            "get songs" -> {
                logger.info("Reindexing all songs")
                val songs = state.loadSongs()
                val s = songs.joinToString(",")
                val osw = OutputStreamWriter(sp.outputStream, "UTF-8")
                osw.write(s)
                osw.write("\n")
                osw.close()
                logger.info("Sending songs to flipper")
            }

            else -> {
                val lc = content.split(" ")

                if (lc.size != 3) {
                    logger.error("Please enter a valid command")
                    continue
                }

                when (lc.getOrNull(1)) {
                    "freq" -> {
                        state.selectedFrequency = lc[2]
                    }

                    "song" -> {
                        try {
                            val idx = lc[2].toInt()

                            if (idx > state.songs.size) {
                                logger.error("Song index $idx out of range.")
                            }
                            state.selectedSongIdx = idx

                        } catch (e: NumberFormatException) {
                            logger.error("Could not parse song index: ${lc[2]}")
                        }
                    }

                    else -> {
                        logger.error("Command unrecognized: $content")
                    }
                }
            }
        }
    }
}