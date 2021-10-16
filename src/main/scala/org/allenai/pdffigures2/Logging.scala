package org.allenai.pdffigures2

// Copied org.allenai.common.Logging (https://github.com/allenai/common)

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.html.HTMLLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core._
import ch.qos.logback.core.encoder.{ Encoder, LayoutWrappingEncoder }
import org.slf4j.LoggerFactory

/** This trait is meant to be mixed into a class to provide logging and logging configuration.
 *
 * The enclosed methods provide a Scala-style logging signature where the
 * message is a block instead of a string.  This way the message string is
 * not constructed unless the message will be logged.
 */
trait Logging {
  val internalLogger = LoggerFactory.getLogger(this.getClass)

  object logger {
    // scalastyle:ignore
    def trace(message: => String): Unit =
      if (internalLogger.isTraceEnabled) {
        internalLogger.trace(message)
      }

    def debug(message: => String): Unit =
      if (internalLogger.isDebugEnabled) {
        internalLogger.debug(message)
      }

    def info(message: => String): Unit =
      if (internalLogger.isInfoEnabled) {
        internalLogger.info(message)
      }

    def warn(message: => String): Unit =
      if (internalLogger.isWarnEnabled) {
        internalLogger.warn(message)
      }

    def warn(message: => String, throwable: Throwable): Unit =
      if (internalLogger.isWarnEnabled) {
        internalLogger.warn(message, throwable)
      }

    def error(message: => String): Unit =
      if (internalLogger.isErrorEnabled) {
        internalLogger.error(message)
      }

    def error(message: => String, throwable: Throwable): Unit =
      if (internalLogger.isErrorEnabled) {
        internalLogger.error(message, throwable)
      }
  }

  /** Simple logback configuration.
   * Hopefully this will be discoverable by just typing <code>loggerConfig.[TAB]</code>
   *
   * Examples:
   * format: OFF
   * {{{
   * loggerConfig.Logger("org.apache.spark").setLevel(Level.WARN)
   *
   * loggerConfig.Logger().addAppender(
   *   loggerConfig.newPatternLayoutEncoder("%-5level [%thread]: %message%n"),
   *   loggerConfig.newConsoleAppender
   * )
   * }}}
   * format: ON
   */
  object loggerConfig {
    case class Logger(loggerName: String = org.slf4j.Logger.ROOT_LOGGER_NAME) {
      private val logger: ch.qos.logback.classic.Logger =
        LoggerFactory.getLogger(loggerName).asInstanceOf[ch.qos.logback.classic.Logger]

      /** Resets the logger. */
      def reset(): Logger = {
        logger.getLoggerContext.reset()
        this
      }

      /** Simple log level setting. Example:
       * <code>
       * loggerConfig.Logger("org.apache.spark").setLevel(Level.WARN)
       * </code>
       */
      def setLevel(level: Level): Logger = {
        logger.setLevel(level)
        this
      }

      /** Simple log appender creation. Example:
       * <code>
       * loggerConfig.Logger()
       *   .addAppender(
       *     loggerConfig.newPatternLayoutEncoder("%-5level [%thread]: %message%n"),
       *     loggerConfig.newConsoleAppender)
       *   .addAppender(
       *     loggerConfig.newHtmlLayoutEncoder("%relative%thread%level%logger%msg"),
       *     loggerConfig.newFileAppender("./log.html"))
       * </code>
       */
      def addAppender(
                       encoder: Encoder[ILoggingEvent],
                       appender: OutputStreamAppender[ILoggingEvent]
                     ): Logger = {
        val loggerContext = logger.getLoggerContext
        encoder.setContext(loggerContext)
        encoder.start()
        appender.setContext(loggerContext)
        appender.setEncoder(encoder)
        appender.start()
        logger.addAppender(appender)
        this
      }
    }

    def newPatternLayoutEncoder(pattern: String): Encoder[ILoggingEvent] = {
      val encoder = new PatternLayoutEncoder()
      encoder.setPattern(pattern)
      encoder
    }

    def newHtmlLayoutEncoder(pattern: String): Encoder[ILoggingEvent] = {
      new LayoutWrappingEncoder[ILoggingEvent] {
        private val htmlLayout = new HTMLLayout()
        htmlLayout.setPattern(pattern)
        super.setLayout(htmlLayout)

        override def setLayout(layout: Layout[ILoggingEvent]) = {
          throw new Exception("Layout set via Logging.logger.config.htmlLayoutEncoder")
        }

        override def setContext(loggerContext: Context) = {
          htmlLayout.setContext(loggerContext)
          super.setContext(loggerContext)
        }

        override def start() = {
          htmlLayout.start()
          super.start()
        }
      }
    }

    def newConsoleAppender(): OutputStreamAppender[ILoggingEvent] = {
      new ConsoleAppender[ILoggingEvent]()
    }

    def newFileAppender(fileName: String): OutputStreamAppender[ILoggingEvent] = {
      val appender = new FileAppender[ILoggingEvent]()
      appender.setAppend(false)
      appender.setFile(fileName)
      appender
    }
  }
}