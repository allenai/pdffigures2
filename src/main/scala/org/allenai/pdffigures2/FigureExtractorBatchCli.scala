package org.allenai.pdffigures2

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import ch.qos.logback.classic.{ Level, Logger }
import org.allenai.pdffigures2.FigureExtractor.DocumentWithSavedFigures
import org.allenai.pdffigures2.JsonProtocol._
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.LoggerFactory

import scala.collection.parallel.ForkJoinTaskSupport

/** CLI tools to parse a batch of PDFs, and then save the figures, table, captions
  * or text to disk.
  */
object FigureExtractorBatchCli extends Logging {

  case class ProcessingStatistics(
    filename: String,
    numPages: Int,
    numFigures: Int,
    timeInMillis: Long
  )
  case class ProcessingError(filename: String, msg: Option[String], className: String)
  implicit val processingStatisticsFormat = jsonFormat4(ProcessingStatistics.apply)
  implicit val processingErrorFormat = jsonFormat3(ProcessingError.apply)

  case class CliConfigBatch(
    inputFiles: Seq[File] = Seq(),
    figureDataPrefix: Option[String] = None,
    dpi: Int = 150,
    ignoreErrors: Boolean = false,
    saveStats: Option[String] = None,
    saveRegionlessCaptions: Boolean = false,
    threads: Int = 1,
    debugLogging: Boolean = true,
    fullTextPrefix: Option[String] = None,
    figureImagePrefix: Option[String] = None,
    figureFormat: String = "png"
  )

  val Parser = new scopt.OptionParser[CliConfigBatch]("figure-extractor-batch") {
    head("figure-extractor-batch")
    arg[Seq[String]]("<input>") required () action { (i, c) =>
      val inputFiles =
        if (i.size == 1) {
          val file = new File(i.head)
          if (file.isDirectory) {
            file.listFiles().toList
          } else {
            List(file)
          }
        } else {
          i.map(f => new File(f)).toList
        }
      c.copy(inputFiles = inputFiles)
    } text "input PDF(s) or directory containing PDFs"
    opt[Int]('i', "dpi") action { (dpi, c) =>
      c.copy(dpi = dpi)
    } text
      "DPI to save the figures in (default 150)" validate { dpi =>
      if (dpi > 0) success else failure("DPI must > 0")
    }
    opt[String]('s', "save-stats") action { (s, c) =>
      c.copy(saveStats = Some(s))
    } validate { s =>
      val f = new File(s)
      if (!f.exists() || f.canWrite && !f.isDirectory) {
        success
      } else {
        failure(s"Can't write to file $s")
      }
    } text "Save the errors and timing information to the given file in JSON fromat"
    opt[Int]('t', "threads") action { (t, c) =>
      c.copy(threads = t)
    } validate { t =>
      if (t >= 0) success else failure("Threads must be >= 0")
    } text "Number of threads to use, 0 means using Scala's default"
    opt[Unit]('e', "ignore-error") action { (_, c) =>
      c.copy(ignoreErrors = true)
    } text "Don't stop on errors, errors will be logged and also saved in `save-stats` if set"
    opt[Unit]('q', "quiet") action { (_, c) =>
      c.copy(debugLogging = false)
    } text "Switches logging to INFO level"
    opt[String]('d', "figure-data-prefix") action { (o, c) =>
      c.copy(figureDataPrefix = Some(o))
    } text "Save JSON figure data to '<data-prefix><input_filename>.json'"
    opt[Unit]('c', "save-regionless-captions") action { (_, c) =>
      c.copy(saveRegionlessCaptions = true)
    } text "Include captions for which no figure regions were found in the JSON data"
    opt[String]('g', "full-text-prefix") action { (f, c) =>
      c.copy(fullTextPrefix = Some(f))
    } text "Save the document and figures into '<full-text-prefix><input_filename>.json"
    opt[String]('m', "figure-prefix") action { (f, c) =>
      c.copy(figureImagePrefix = Some(f))
    } text "Save figures as <figure-prefix><input_filename>-<Table|Figure><Name>-<id>.png. `id` " +
      "will be 1 unless multiple figures are found with the same `Name` in `input_filename`"
    opt[String]('f', "figure-format") action { (f, c) =>
      c.copy(figureFormat = f)
    } text "Format to save figures (default png)" validate { x =>
      if (FigureRenderer.AllowedFormats.contains(x)) {
        success
      } else {
        failure(
          s"$x not supported (allowed " +
            s"formats: ${FigureRenderer.AllowedFormats.mkString(",")}"
        )
      }
    }
    checkConfig { c =>
      val badFiles =
        c.inputFiles.find(f => !f.exists() || f.isDirectory || !f.getName.endsWith(".pdf"))
      if (badFiles.isDefined) {
        failure(s"Input file ${badFiles.get.getName} is not a PDF file")
      } else if (c.saveRegionlessCaptions && c.fullTextPrefix.isDefined) {
        failure(s"Can't set both save-regionless-captions and full-text")
      } else if (c.fullTextPrefix.isDefined && c.figureDataPrefix.isDefined) {
        failure(s"Can't set both full-text and figure-data-prefix")
      } else {
        success
      }
    }
  }

  def getFilenames(
    prefix: String,
    docName: String,
    format: String,
    figures: Seq[Figure]
  ): Seq[String] = {
    val namesUsed = scala.collection.mutable.Map[String, Int]()
    figures.map { fig =>
      val figureName = s"${fig.figType}${fig.name}"
      namesUsed.update(figureName, namesUsed.getOrElse(figureName, 1))
      val id = namesUsed(figureName)
      val filename = s"$prefix$docName-$figureName-$id.$format"
      filename
    }
  }

  def saveRasterizedFigures(
    prefix: String,
    docName: String,
    format: String,
    dpi: Int,
    figures: Seq[RasterizedFigure],
    doc: PDDocument
  ): Seq[SavedFigure] = {
    val filenames = getFilenames(prefix, docName, format, figures.map(_.figure))
    FigureRenderer.saveRasterizedFigures(filenames.zip(figures), format, dpi)
  }

  def processFile(
    inputFile: File,
    config: CliConfigBatch
  ): Either[ProcessingError, ProcessingStatistics] = {
    val fileStartTime = System.nanoTime()
    var doc: PDDocument = null
    val figureExtractor = FigureExtractor()
    try {
      doc = PDDocument.load(inputFile)
      val useCairo = FigureRenderer.CairoFormat.contains(config.figureFormat)
      val inputName = inputFile.getName
      val truncatedName = inputName.substring(0, inputName.lastIndexOf('.'))
      val numFigures = if (config.fullTextPrefix.isDefined) {
        val outputFilename = s"${config.fullTextPrefix.get}$truncatedName.json"
        val numFigures = if (config.figureImagePrefix.isDefined && !useCairo) {
          val document = figureExtractor.getRasterizedFiguresWithText(doc, config.dpi)
          val savedFigures = saveRasterizedFigures(
            config.figureImagePrefix.get,
            truncatedName,
            config.figureFormat,
            config.dpi,
            document.figures,
            doc
          )
          val documentWithFigures =
            DocumentWithSavedFigures(savedFigures, document.abstractText, document.sections)
          FigureRenderer.saveAsJSON(outputFilename, documentWithFigures)
          document.figures.size
        } else {
          val document = figureExtractor.getFiguresWithText(doc)
          if (useCairo) {
            val filenames = getFilenames(
              config.figureImagePrefix.get,
              truncatedName,
              config.figureFormat,
              document.figures
            )
            val savedFigures = FigureRenderer
              .saveFiguresAsImagesCairo(
                doc,
                filenames.zip(document.figures),
                config.figureFormat,
                config.dpi
              )
              .toSeq
            val savedDocument =
              DocumentWithSavedFigures(savedFigures, document.abstractText, document.sections)
            FigureRenderer.saveAsJSON(outputFilename, savedDocument)
          } else {
            FigureRenderer.saveAsJSON(outputFilename, document)
          }
          document.figures.size
        }
        numFigures
      } else {
        val (figures, failedCaptions) = if (config.figureImagePrefix.isDefined && !useCairo) {
          val figuresWithErrors = figureExtractor.getRasterizedFiguresWithErrors(doc, config.dpi)
          val savedFigures = saveRasterizedFigures(
            config.figureImagePrefix.get,
            truncatedName,
            config.figureFormat,
            config.dpi,
            figuresWithErrors.figures,
            doc
          )
          (Left(savedFigures), figuresWithErrors.failedCaptions)
        } else {
          val figuresWithErrors = figureExtractor.getFiguresWithErrors(doc)
          if (useCairo) {
            val filenames = getFilenames(
              config.figureImagePrefix.get,
              truncatedName,
              config.figureFormat,
              figuresWithErrors.figures
            )
            val savedFigures = FigureRenderer
              .saveFiguresAsImagesCairo(
                doc,
                filenames.zip(figuresWithErrors.figures),
                config.figureFormat,
                config.dpi
              )
              .toSeq
            (Left(savedFigures), figuresWithErrors.failedCaptions)
          } else {
            (Right(figuresWithErrors.figures), figuresWithErrors.failedCaptions)
          }
        }
        if (config.figureDataPrefix.isDefined) {
          val outputFilename = s"${config.figureDataPrefix.get}$truncatedName.json"
          if (config.saveRegionlessCaptions) {
            val toSave: Map[String, Either[Either[Seq[SavedFigure], Seq[Figure]], Seq[Caption]]] =
              Map(
                "figures" -> Left(figures),
                "regionless-captions" -> Right(failedCaptions)
              )
            FigureRenderer.saveAsJSON(outputFilename, toSave)
          } else {
            val toSave: Either[Seq[SavedFigure], Seq[Figure]] = figures
            FigureRenderer.saveAsJSON(outputFilename, toSave)
          }
        }
        figures match {
          case Left(savedFigures) => savedFigures.size
          case Right(figs) => figs.size
        }
      }
      val timeTaken = System.nanoTime() - fileStartTime
      logger.info(s"Finished ${inputFile.getName} in ${(timeTaken / 1000000) / 1000.0} seconds")
      Right(
        ProcessingStatistics(
          inputFile.getAbsolutePath,
          doc.getNumberOfPages,
          numFigures,
          timeTaken / 1000000
        )
      )
    } catch {
      case e: Exception =>
        if (config.ignoreErrors) {
          logger.info(s"Error: $e on document ${inputFile.getName}")
          Left(
            ProcessingError(
              inputFile.getAbsolutePath,
              Option(e.getMessage),
              e.getClass.getName
            )
          )
        } else {
          throw e
        }
    } finally {
      if (doc != null) doc.close()
    }
  }

  def run(config: CliConfigBatch): Unit = {
    val startTime = System.nanoTime()
    if (!config.debugLogging) {
      val root = LoggerFactory.getLogger("root").asInstanceOf[Logger]
      root.setLevel(Level.INFO)
    }
    val results = if (config.threads == 1) {
      config.inputFiles.zipWithIndex.map {
        case (inputFile, fileNum) =>
          logger.info(
            s"Processing file ${inputFile.getName} " +
              s"(${fileNum + 1} of ${config.inputFiles.size})"
          )
          processFile(inputFile, config)
      }
    } else {
      val parFiles = config.inputFiles.par
      if (config.threads != 0) {
        parFiles.tasksupport = new ForkJoinTaskSupport(
          new scala.concurrent.forkjoin.ForkJoinPool(config.threads)
        )
      }
      val onPdf = new AtomicInteger(0)
      parFiles.map { inputFile =>
        val curPdf = onPdf.incrementAndGet();
        logger.info(
          s"Processing file ${inputFile.getName} " +
            s"($curPdf of ${config.inputFiles.size})"
        )
        processFile(inputFile, config)
      }.toList
    }
    val totalTime = System.nanoTime() - startTime
    logger.info(s"Finished processing ${config.inputFiles.size} files")
    logger.info(s"Took ${(totalTime / 1000000) / 1000.0} seconds")

    if (config.saveStats.isDefined) {
      FigureRenderer.saveAsJSON(config.saveStats.get, results)
      logger.info(s"Stats saved to ${config.saveStats.get}")
    }
    val errors = results.flatMap {
      case Left(pe) => Some(pe)
      case _ => None
    }
    if (errors.isEmpty) {
      logger.info(s"No errors")
    } else {
      val errorString = errors
        .map {
          case ProcessingError(name, msg, errorName) => s"$name: $errorName: $msg"
        }
        .mkString("\n")
      if (config.saveStats.isDefined) {
        logger.info(s"Errors ${errors.size} files")
      } else {
        logger.info(s"Errors on the following files:\n$errorString")
      }
    }
  }

  def main(args: Array[String]): Unit = {
    Parser.parse(args, CliConfigBatch()) match {
      case Some(config) => run(config)
      case None => System.exit(1)
    }
  }
}
