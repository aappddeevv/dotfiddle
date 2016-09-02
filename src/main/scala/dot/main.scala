package dot

import scala.language._
import scalafx.Includes._
import scalafx.application._
import JFXApp.PrimaryStage
import scalafx.scene._
import scalafx.scene.control._
import scalafx.scene.layout._
import scalafx.scene.input._
import scalafx.stage.FileChooser.ExtensionFilter
import scalafx.stage._
import scalafx.scene.web._
import scalafx.geometry._
import control.Alert.AlertType
import scalafx.scene.control.cell._
import scalafx.beans.property._
import scalafx.scene.control.TableColumn._
import scalafx.beans.binding.Bindings
import java.nio.file._
import java.nio.charset.StandardCharsets
import scala.util.control.Exception._
import java.io._

/**
 * A graphviz option.
 *
 *  @param key The key of the option.
 *  @param value The value of the option
 *  @param active True if the option should be used when running the graphviz commands.
 */
class AnOption(_key: String, _value: String, _active: Boolean) {
  import scalafx.beans.property.StringProperty
  val key = new StringProperty(_key)
  val value = new StringProperty(_value)
  val active = new BooleanProperty(this, "active", _active)
  def render() =
    if (active() && key().trim.size > 0 && value().trim.size > 0)
      Some(key().trim + "=" + value().trim)
    else
      None
}

/**
 *  Captures the state of all user configurable elements
 *  so you can jump between different dotfiddles.
 */
case class DotFiddle(graphProps: Seq[AnOption] = Seq(), edgeProps: Seq[AnOption] = Seq(), nodeProps: Seq[AnOption] = Seq(),
  source: Option[String] = None, image: Option[String] = None,
  extraArgs: Option[String] = None,
  scale: Option[Double] = Option(1.0))

/**
 *   Main stage.
 */
object main extends JFXApp {

  val titlePrefix = "dotfiddle"
  
  stage = new PrimaryStage {
    title = titlePrefix
    onCloseRequest = handle {
      Platform.exit()
      System.exit(0)
    }

    /** Exit the application. */
    val exitButton = new MenuItem("E_xit") {
      onAction = { ae => stage.close() }
    }

    /** Currently loaded dot file. */
    var dotFile: Option[Path] = None
    /** Original dot source if from file. */
    private var dotSource: Option[String] = None

    val gvExts = Seq(new ExtensionFilter("gv Files", "*.gv"),
            new ExtensionFilter("All Files", "*.*")).map(_.delegate)
    
    
    /** Load a dot file. */
    val loadButton = new MenuItem("Open") {
      accelerator = KeyCombination.keyCombination("Ctrl+O")
      onAction = { ae =>
        val fileChooser = new FileChooser {
          title = "Open SVG File"
          extensionFilters ++= gvExts
        }
        val selectedFile = Option(fileChooser.showOpenDialog(stage))
        selectedFile map (_.toPath) foreach { file =>
          addMessages(s"Loading dot file: ${file.toAbsolutePath().toString}")
          loadDot(file, stage)
          dotFile = Option(file)
        }
      }
    }

    val saveButton = new MenuItem("Save") {
      accelerator = KeyCombination.keyCombination("Ctrl+S")
      onAction = { _ =>
        dotFile foreach { path =>
          println("doing save")
          nonFatalCatch withApply { t =>
            addMessages(s"Unable to dot source save file ${path.toAbsolutePath.toString}.")
            new Alert(AlertType.Error) {
              initOwner(stage)
              title = "Error Saving To File"
              headerText = s"Unable to save dot source text."
              contentText = s"File: $path"
            }.showAndWait()
          } apply {
            Files.write(path, getDotSource().getBytes(StandardCharsets.UTF_8))
            addMessages(s"Saved dot source to file ${path.toAbsolutePath.toString}.")
          }
        }
        if (dotFile.isEmpty) {
          addMessages("No dot file is loaded to save.")
        }
      }
    }

    val saveAsButton = new MenuItem("Save _As") {
      onAction = { ae =>
        val fileChooser = new FileChooser {
          title = "Save DOT  FIle"
          extensionFilters ++= gvExts
        }
        val selectedFile = Option(fileChooser.showOpenDialog(stage))
        selectedFile foreach { file =>
          Files.write(file.toPath, getDotSource().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE)
        }
      }
    }

    /**
     * Run dot in the OS.
     */
    trait DotRunner {
      import scala.sys.process._
      import java.nio.file._
      import java.nio.charset.StandardCharsets
      import scala.util.control.Exception._

      val command = "dot -Tsvg "
      val inputFile = "temp.gv"
      val outputFile = "temp.svg"

      val extraArgs = new scalafx.beans.property.StringProperty()

      val catchTempFileMgmtError = nonFatalCatch withApply { t =>
        new Alert(AlertType.Error) {
          initOwner(stage)
          title = "Error Creating Temp File"
          headerText = s"Unable to create or clean up temporary output file(s): $outputFile or $inputFile."
          contentText = s"Unable to generate picture. Error: ${t.getMessage}"
        }.showAndWait()
        false
      }

      /** Generate the command line basic options from the UI specifications. */
      def opts() = s""" ${Option(extraArgs.value).getOrElse("")} """ + generateOptions2()

      def run(source: String): Unit = {
        val cleanedUp = catchTempFileMgmtError apply {
          Files.write(Paths.get(inputFile), source.getBytes(StandardCharsets.UTF_8))
          val ofile = Paths.get(outputFile)
          if (Files.exists(ofile)) Files.delete(ofile)
          true
        }

        if (cleanedUp) {
          val stdout = new StringBuilder
          val stderr = new StringBuilder
          val commandToRun = command + opts() + s" -o$outputFile " + s" $inputFile"
          addMessages(s"Rendering command:\n$commandToRun")
          val ecode = commandToRun ! ProcessLogger(stdout append _, stderr append _)
          if (ecode != 0) {
            new Alert(AlertType.Error) {
              initOwner(stage)
              title = "Error Creating Graph"
              headerText = "Unable to create graph using external OS process."
              contentText = s"Error code: $ecode\nCommand Output:\n${stdout.toString}"
            }.showAndWait()
          } else {
            // load the output file
            reload(Some(outputFile))
          }
          if (stdout.length > 0) addMessages(stdout.toString)
          if (stderr.length > 0) addMessages(stderr.toString)
        }
      }
    }
    val runner = new DotRunner() {}

    /** Render the dot source into an image to display. */
    def render(): Unit = {
      val source = getDotSource().trim
      Platform.runLater {
        runner.run(source)
      }
    }

    /**
     * Reload the SVG file that is output from graphviz rendering.
     */
    def reload(svg: Option[String] = None): Unit = {
      svg.foreach { f =>
        val path = java.nio.file.Paths.get(f)
        addMessages(s"Loading SVG: ${path.toAbsolutePath}")
        Platform.runLater {
          val eng = svgView.getEngine()
          eng.load("file:///" + path.toAbsolutePath())
        }
      }
    }

    val (extraArgs, extraArgsView) = {
      val args = new TextField() {
        editable = true
      }
      val view = new HBox {
        spacing = 10
        children ++= List(new Label("Extra Args:"): Node, args: Node)
      }
      (args, view)
    }
    runner.extraArgs <== extraArgs.text

    import org.fxmisc.richtext._
    import org.fxmisc.flowless._

    val editor = new CodeArea()
    editor.setParagraphGraphicFactory(LineNumberFactory.get(editor))

    /** Get the dot source from the text editor. Should never return null. */
    def getDotSource(): String = editor.getDocument.getText

    /** Set the dot source from the string. */
    def setDotSource(source: String) = editor.replaceText(0, 0, source)

    val (slider, sview) = {
      val slider = new Slider(0.1, 5, 0.25) { blockIncrement = 0.25f }
      val view = new HBox {
        spacing = 10
        children ++= List(new Label("Zoom:"): Node, slider: Node)
      }
      (slider, view)
    }

    val svgView = {
      val view = new WebView()
      view.setMinSize(500, 400)
      view
    }
    def zoomIn(): Unit = {
      slider.setValue(slider.getValue() + slider.getBlockIncrement())
    }
    def zoomOut(): Unit = {
      slider.setValue(slider.getValue() - slider.getBlockIncrement())
    }
    val zoomer = new ZoomingPane(svgView)
    zoomer.zoomFactorProperty() <== slider.value

    /** Create tables to capture graph, node or edge options. */
    def makeTable(model: scalafx.collections.ObservableBuffer[AnOption] = new scalafx.collections.ObservableBuffer[AnOption](),
      gen: => AnOption = { new AnOption("key", "value", true) }) = {
      val v = new scalafx.scene.control.TableView[AnOption] {
        editable = true
        items = model
        columns ++= List(
          new TableColumn[AnOption, String] {
            text = "Key"
            prefWidth = 75
            cellValueFactory = { _.value.key }
            editable = true
            cellFactory = TextFieldTableCell.forTableColumn[AnOption]
          },
          new TableColumn[AnOption, String] {
            text = "Value"
            prefWidth = 75
            cellValueFactory = { _.value.value }
            editable = true
            cellFactory = TextFieldTableCell.forTableColumn[AnOption]
          },
          new TableColumn[AnOption, java.lang.Boolean] {
            text = "Active"
            cellValueFactory = { _.value.active.delegate }
            editable = true
            cellFactory = CheckBoxTableCell.forTableColumn(this)
          })
        // Add an add and delete context menu
        val cmenu = new ContextMenu()
        val add = new MenuItem("Add") {
          onAction = { ev => model += gen }
        }
        val delete = new MenuItem("Delete") {
          onAction = { ev => model --= selectionModel().selectedItems }
        }
        delete.disable <== javafx.beans.binding.Bindings.isEmpty(this.selectionModel().selectedItems)
        cmenu.items ++= List(add, delete)
        contextMenu() = cmenu
        selectionModel().selectionMode() = SelectionMode.Multiple
      }
      v
    }

    val gtb = makeTable()
    val ggrid = gtb.getItems
    val ntb = makeTable()
    val ngrid = ntb.getItems
    val etb = makeTable()
    val egrid = etb.getItems
    ggrid += new AnOption("layout", "fdp", true)

    /**
     * From the key-value pairs in the graph, node and edge property tables,
     * create a dot command line.
     */
    def generateOptions2(): String = {
      (ggrid.filter { _.active() }.map(_.render).collect { case Some(o) => "-G" + o } ++
        ngrid.filter { _.active() }.map(_.render).collect { case Some(o) => "-N" + o } ++
        egrid.filter { _.active() }.map(_.render).collect { case Some(o) => "-E" + o }).mkString(" ")
    }

    val runButton = new Button() {
      text = "Run"
      onAction = { ae =>
        // run and reload
        render()
      }
    }

    def makeButtons(smodel: scalafx.beans.property.ObjectProperty[javafx.scene.control.TableView.TableViewSelectionModel[AnOption]],
      model: scalafx.collections.ObservableBuffer[AnOption],
      gen: => AnOption = { new AnOption("key", "value", true) }) = {
      new HBox {
        spacing = 10
        padding = Insets(10, 0, 0, 10)
        children ++= List(
          new Button("+") {
            onAction = { ae =>
              model += gen
            }
          }: Node,
          new Button("-") {
            onAction = { ae =>
              smodel().selectedItems.foreach { model remove _ }
            }
            disable <== javafx.beans.binding.Bindings.isEmpty(smodel().selectedItems)
          }: Node)
      }
    }

    import scalafx.scene.control.TabPane._
    import scalafx.geometry._
    val inputs = new TabPane {
      tabClosingPolicy = TabClosingPolicy.Unavailable
      side = Side.Top
      tabs = List(
        new Tab() {
          text = "Graph"
          content = new VBox {
            spacing = 5
            children ++= List(makeButtons(gtb.selectionModel, ggrid), gtb)
          }
        },
        new Tab() {
          text = "Node"
          content = new VBox {
            spacing = 5
            children ++= List(makeButtons(ntb.selectionModel, ngrid), ntb)
          }
        },
        new Tab() {
          text = "Edge"
          content = new VBox {
            spacing = 5
            children ++= List(makeButtons(etb.selectionModel, egrid), etb)
          }
        })
    }

    val commandCenter = new VBox {
      spacing = 10
      children addAll (sview, extraArgsView, inputs)
    }

    val splitter = new SplitPane {
      items ++= Seq(commandCenter, zoomer, editor)
      dividerPositions_=(0.20, 0.70)
    }

    val ctrlR = new KeyCodeCombination(KeyCode.R, KeyCombination.ShortcutDown)
    val ctrlPlus = new KeyCodeCombination(KeyCode.Plus, KeyCombination.ShortcutDown)
    val ctrlMinus = new KeyCodeCombination(KeyCode.Minus, KeyCombination.ShortcutDown)

    val messages = new TextArea() { editable = false }
    var mcounter: Int = 1

    /** Add message text. */
    def addMessages(text: String): Unit = {
      if (text.trim != 0) {
        import java.time._
        import java.time.format._
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
          .withZone(ZoneId.systemDefault());
        val counter = mcounter
        mcounter = mcounter + 1
        import java.time._
        Platform.runLater {
          messages.appendText(s"\n[$counter]: ${formatter.format(Instant.now)}\n")
          messages.appendText(text)
          messages.appendText("\n")
        }
      }
    }

    /**
     *  Load dot file and reset graphics.
     */
    def loadDot(file: Path, stage: Stage): Unit = {
      def showAlert(f: String) = {
          new Alert(AlertType.Error) {
            initOwner(stage)
            title = "Error Loading File"
            headerText = s"File not found. Unable to load dot file."
            contentText = s"File: $f"
          }.showAndWait()
        addMessages(s"dot source file $f not found.")
      }

      nonFatalCatch withApply { t =>
        showAlert(file.toAbsolutePath().toString)
        dotSource = None
      } apply {
        if (Files.exists(file)) {
          val text = scala.io.Source.fromFile(file.toFile).getLines.mkString("\n")
          // load dot content into editor, render once
          setDotSource(text)
          dotSource = Option(text)
          render()
          stage.title = titlePrefix + " - " + file.getFileName.toString
        }
      }
    }

    /**
     * Snapshot core set of user elements.
     */
    def snapshot(): DotFiddle = {
      DotFiddle(ggrid.toSeq, egrid.toSeq, ngrid.toSeq,
        source = Some(editor.getDocument.getText),
        extraArgs = Some(extraArgs.text.get()),
        image = dotSource, scale = Some(slider.value()))
    }

    scene = new Scene(1020, 700) {
      root = new BorderPane {
        top = new VBox { // in case I add a toolbar
          children = new MenuBar {
            menus = List(
              new Menu("_File") {
                items = List(
                  loadButton,
                  saveButton,
                  saveAsButton,
                  new SeparatorMenuItem(),
                  exitButton)
              },
              new Menu("_Edit") {
                items = List(
                  new MenuItem("Cut") {},
                  new MenuItem("Copy") {},
                  new MenuItem("Paste") {},
                  new SeparatorMenuItem(),
                  new MenuItem("Copy Command Line Options to Clipboard") {
                    onAction = { _ =>
                      val content = new ClipboardContent()
                      content.putString(runner.opts())
                      scalafx.scene.input.Clipboard.systemClipboard.setContent(content)
                    }
                  },
                  new SeparatorMenuItem(),
                  new MenuItem("Preferences") {})
              },
              new Menu("_View") {
                items = List(
                  new MenuItem("_Refresh") {
                    accelerator = ctrlR
                    onAction = { ae => render() }
                  },
                  new MenuItem("Zoom In") {
                    accelerator = ctrlPlus
                    onAction = { ae => zoomIn() }
                  },
                  new MenuItem("Zoom Out") {
                    accelerator = ctrlMinus
                    onAction = { ae => zoomOut() }
                  })
              })
          }
        }
        center = splitter
        bottom = messages
      }
    }
  }
}
