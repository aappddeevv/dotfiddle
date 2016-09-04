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
 * A graphviz option. These get translated to command line parameters
 * that override those in the graph file.
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
case class DotFiddleState(graphProps: Seq[AnOption] = Seq(), edgeProps: Seq[AnOption] = Seq(), nodeProps: Seq[AnOption] = Seq(),
  source: Option[String] = None, image: Option[String] = None,
  extraArgs: Option[String] = None,
  scale: Option[Double] = Option(1.0))

case class Config(
  help: Boolean = false,
  dotCommand: String = "dot -Tsvg")

object Main {

  val parser = new scopt.OptionParser[Config]("dotfiddle") {
    override def showUsageOnError = true
    head("dotfiddle", "0.1.0")
    opt[String]("dotcommand").text("""Set the base command used to render graphics. Default is "dot -Tsvg"""")
      .action((x, c) => c.copy(dotCommand = x))
    help("help").text("Show help.")
  }

  var config: Config = _

  def main(args: Array[String]): Unit = {

    config = parser.parse(args, Config()) match {
      case Some(c) => c
      case _ => return
    }
    javafx.application.Application.launch(classOf[DotFiddle], args: _*)
  }

}

class DotFiddle extends javafx.application.Application {

  val titlePrefix = "dotfiddle"
  var stage: scalafx.stage.Stage = _ // ouch!

  override def start(stage: javafx.stage.Stage): Unit = {
    stage.title = titlePrefix
    stage.onCloseRequest = handle {
      Platform.exit()
      System.exit(0)
    }
    this.stage = stage
    stage.scene = scene

    // Get default graph to show
    val defaultGraph =
      nonFatalCatch withApply { ex =>
        """// place your graph code in graphvz format here
a -- b
b -- c
c -- d 
}"""
      } opt {
        val p = getClass.getResource("/default.gv")
        scala.io.Source.fromFile(p.toURI()).mkString
      }
    setDotSourceAndRender(defaultGraph)
    stage.show
  }

  /**
   * Get a graph file from the jar file via resources.
   */
  protected def getGV(resource: String) =
    nonFatalCatch withApply { ex =>
      """// place your graph code in graphvz format here"""
    } apply {
      val p = getClass.getResource(resource)
      scala.io.Source.fromFile(p.toURI()).mkString
    }

  override def stop(): Unit = {
    runner.shutdown()
  }

  /** Exit the application. */
  val exitButton = new MenuItem("E_xit") {
    onAction = { ae => stage.close() }
  }

  /** Currently loaded dot file. */
  var dotFile: Option[Path] = None
  /** Original dot source if from file. */
  private var dotSource: Option[String] = None

  val gvExts = Seq(
    new ExtensionFilter("gv Files", "*.gv"),
    new ExtensionFilter("dot files", "*.dot"),
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
   * Run dot in the OS. Temporary files are re-used run to run.
   */
  trait DotRunner {
    import scala.sys.process._
    import java.nio.file._
    import java.nio.charset.StandardCharsets
    import scala.util.control.Exception._

    val dotCommand = "dot -Tsvg"
    val inputFile = Files.createTempFile("dotfiddle", ".gv")
    val outputFile = Files.createTempFile("dotfiddle", ".svg")
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

    def shutdown(): Unit = {
      clean()
    }

    protected def clean(): Unit = {
      nonFatalCatch apply { // do nothing if error
        Files.deleteIfExists(inputFile)
        Files.deleteIfExists(outputFile)
      }
    }

    /** Generate the command line basic options from the UI specifications. */
    def opts() = s""" ${Option(extraArgs.value).getOrElse("")} """ + generateOptions2()

    def run(source: String): Unit = {
      val cleanedUp = catchTempFileMgmtError apply {
        Files.write(inputFile, source.getBytes(StandardCharsets.UTF_8))
        Files.deleteIfExists(outputFile)
        true
      }

      if (cleanedUp) {
        val stdout = new StringBuilder
        val stderr = new StringBuilder
        val commandToRun = dotCommand + " " + opts() + s" -o$outputFile " + s" $inputFile"
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
          loadAndDisplaySVG(Some(outputFile))
        }
        //if (stdout.length > 0) addMessages(stdout.toString)
        if (stderr.length > 0) addMessages("Error output from running rendering: " + stderr.toString)
      }
    }
  }
  val runner = new DotRunner() {
    override val dotCommand = Main.config.dotCommand

  }

  /** Render the dot source into an image to display. */
  def render(): Unit = {
    val source = getDotSource().trim
    runner.run(source)
  }

  /**
   * Reload the SVG file that is output from graphviz rendering.
   */
  def loadAndDisplaySVG(svg: Option[Path] = None): Unit = {
    svg match {
      case Some(f) if (Files.exists(f) && Files.size(f) > 0) =>
        addMessages(s"Loading SVG: ${f.toAbsolutePath.toString}")
        Platform.runLater {
          val eng = svgView.getEngine()
          eng.load("file:///" + f.toAbsolutePath.toString)
        }
      case _ =>
        val eng = svgView.getEngine
        eng.loadContent("<p>No graph to render.</p>")
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

  /**
   * Editor view with a builtin search highlighting box.
   */
  class EditorView() extends javafx.scene.layout.VBox() {
    val editor = new InlineCssTextArea()
    editor.setWrapText(true)
    editor.setParagraphGraphicFactory(LineNumberFactory.get(editor))
    val spane = new VirtualizedScrollPane[InlineCssTextArea](editor)
    val search = new TextField()
    setSpacing(10)
    VBox.setVgrow(spane, Priority.Always)
    getChildren() ++= List(
      new HBox {
        spacing = 5
        children ++= List(new Label("Search Regex:"), search, new Button("Reapply") {
          onAction = { ae => reapplySearchHighlighting() }
        }: Node, new Button("Clear") {
          onAction = { ae => search.text = "" }
        }: Node)
      }: Node,
      spane)

    // When return pressed in searchbox, do the highlighting. */
    search.textProperty().onChange {
      reapplySearchHighlighting()
    }

    // Reapply highlighting as you type...very expensive.
    search.onKeyPressed = { _ =>
      reapplySearchHighlighting()
    }

    def getText() = editor.getDocument.getText

    def setText(source: String) = {
      editor.replaceText(0, editor.getLength, source)
      highlightUsing(search.text())
    }

    /** Clear all style sthen apply styles based on regex. */
    def highlightUsing(regex: String): Unit = {
      editor.clearStyle(0, editor.getLength)
      nonFatalCatch withApply { f =>
        addMessages(s"Invalid regex: $regex")
      } apply {
        regex.r.findAllMatchIn(getText()).foreach { m =>
          editor.setStyle(m.start, m.end, "-fx-fill: red")
        }
      }
    }

    /** Reapply highlighting to the entire document based on the current search box. */
    def reapplySearchHighlighting(): Unit = {
      if (search.text().trim.length > 0)
        highlightUsing(search.text())
    }
  }

  val editorPane = new EditorView()

  /** Get the dot source from the text editor. Should never return null. */
  def getDotSource(): String = editorPane.getText()

  /** Set the editor's dot source from the string. */
  def setDotSource(source: String) = editorPane.setText(source)

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
      styleClass ++= Seq("defaults-table")
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
    id = "defaults-pane"
    tabClosingPolicy = TabClosingPolicy.Unavailable
    side = Side.Top
    tabs = List(
      new Tab() {
        styleClass ++= Seq("defaults-tab")
        id = "defaults-tab-node"
        text = "Graph"
        content = new VBox {
          spacing = 5
          children ++= List(makeButtons(gtb.selectionModel, ggrid), gtb)
        }
      },
      new Tab() {
        styleClass ++= Seq("defaults-tab")
        id = "defaults-tab-node"
        text = "Node"
        content = new VBox {
          spacing = 5
          children ++= List(makeButtons(ntb.selectionModel, ngrid), ntb)
        }
      },
      new Tab() {
        styleClass ++= Seq("defaults-tab")
        id = "defaults-tab-edge"
        text = "Edge"
        content = new VBox {
          spacing = 5
          children ++= List(makeButtons(etb.selectionModel, egrid), etb)
        }
      })
  }

  val commandCenter = new VBox {
    spacing = 10
    VBox.setVgrow(inputs, Priority.Always)
    children addAll (extraArgsView, inputs)
  }

  val splitter = new SplitPane {
    items ++= Seq(
      commandCenter,
      new VBox {
        spacing = 10
        VBox.setVgrow(zoomer, Priority.Always)
        children ++= Seq(sview, zoomer)
      }: Node,
      editorPane)
    dividerPositions_=(0.20, 0.70)
  }

  val ctrlR = new KeyCodeCombination(KeyCode.R, KeyCombination.ShortcutDown)
  val ctrlPlus = new KeyCodeCombination(KeyCode.Plus, KeyCombination.ShortcutDown)
  val ctrlMinus = new KeyCodeCombination(KeyCode.Minus, KeyCombination.ShortcutDown)

  class MessagesView() extends javafx.scene.layout.VBox {

    val messages = new TextArea() { editable = false }
    var mcounter: Int = 1

    getChildren() ++= List(new Label("Messages"), messages: Node)
    
    /** Add a user message. */
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
  }
  val messagesView = new MessagesView()

  /** Add a user message. */
  def addMessages(text: String) = messagesView.addMessages(text)

  /**
   * Set's the editor's dot source to the string and requests a render.
   */
  def setDotSourceAndRender(text: Option[String]): Unit = {
    setDotSource(text.map(_.trim).filterNot(_.isEmpty).getOrElse(""))
    dotSource = text
    render()
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
      setDotSourceAndRender(None)
      stage.title = titlePrefix
    } apply {
      if (Files.exists(file)) {
        val text = scala.io.Source.fromFile(file.toFile).getLines.mkString("\n")
        // load dot content into editor, render once
        setDotSourceAndRender(Some(text))
        stage.title = titlePrefix + " - " + file.getFileName.toString
      }
    }
  }

  /**
   * Snapshot core set of user elements.
   */
  //    def snapshot(): DotFiddle = {
  //      DotFiddleState(ggrid.toSeq, egrid.toSeq, ngrid.toSeq,
  //        source = Some(editor.getDocument.getText),
  //        extraArgs = Some(extraArgs.text.get()),
  //        image = dotSource, scale = Some(slider.value()))
  //    }

  val examples = Seq("default", "ER", "fsm", "process",
    "prof", "psg", "softmaint", "unix", "world")

  val scene = new Scene(1020, 700) {
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
                new Menu("Loa_d Examples") {
                  items = examples.map { f =>
                    new MenuItem(f) {
                      onAction = { _ =>
                        val gv = getGV("/" + f + ".gv")
                        setDotSourceAndRender(Some(gv))
                      }
                    }
                  }
                },
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
      bottom = messagesView
    }
  }

  scene.getStylesheets += getClass.getResource("/dotfiddle.css").toExternalForm
  //stage.icons += new javafx.scene.image.Image(getClass.getResource("/dotfiddle.png").toExternalForm)
}
