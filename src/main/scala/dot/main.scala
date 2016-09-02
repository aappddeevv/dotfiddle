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

object main extends JFXApp {

  stage = new PrimaryStage {
    title = "dotfiddle"
    onCloseRequest = handle {
      Platform.exit()
      System.exit(0)
    }

    /** Exit the application. */
    val exitButton = new MenuItem("E_xit") {
      onAction = { ae => stage.close() }
    }

    /** Load a SVG file. */
    val loadButton = new MenuItem("Open") {
      accelerator = KeyCombination.keyCombination("Ctrl+O")
      onAction = { ae =>
        val fileChooser = new FileChooser {
          title = "Open SVG File"
          extensionFilters ++= Seq(new ExtensionFilter("dot Files", "*.dot"),
            new ExtensionFilter("All Files", "*.*"))
        }
        val selectedFile = fileChooser.showOpenDialog(stage)
        if (selectedFile != null) {
          println(s"Loading dot file: $selectedFile")
          loadDot(Some(selectedFile))
        }
      }
    }

    val saveButton = new MenuItem("Save") {
      accelerator = KeyCombination.keyCombination("Ctrl+S")
      onAction = { ae =>
      }
    }

    val saveAsButton = new MenuItem("Save _As") {
      onAction = { ae =>
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
      val inputFile = "temp.dot"
      val outputFile = "temp.svg"

      val extraArgs = new scalafx.beans.property.StringProperty()

      def run(source: String): Unit = {
        // write source to temp file
        nonFatalCatch withApply { t =>
          new Alert(AlertType.Error) {
            initOwner(stage)
            title = "Error Creating Temp File"
            headerText = s"Unable to create or clean up temporary output file(s): $outputFile or $inputFile."
            contentText = s"Unable to generate picture. Error: ${t.getMessage}"
          }.showAndWait()
          return
        } apply {
          Files.write(Paths.get(inputFile), source.getBytes(StandardCharsets.UTF_8))
          val ofile = Paths.get(outputFile)
          if (Files.exists(ofile)) Files.delete(ofile)
        }

        // run the command, specify the output file and temp input file
        val stdout = new StringBuilder
        val stderr = new StringBuilder
        val commandToRun = command + s""" ${Option(extraArgs.value).getOrElse("")} """ +
          generateOptions2() +
          s" -o$outputFile " + s" $inputFile"
        println("Command: " + commandToRun)
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
    val runner = new DotRunner() {}

    import java.nio.file._
    import java.io._
    /**
     *  Load dot file and reset graphics.
     */
    def loadDot(file: Option[File] = None): Unit = {
      file.foreach { f =>
        if (Files.exists(f.toPath)) {
          val text = scala.io.Source.fromFile(f).getLines.mkString("\n")
          // load dot content into editor
          setDotSource(text)
          // force one render cycle
          render()
        } else {
          new Alert(AlertType.Error) {
            initOwner(stage)
            title = "Error Loading File"
            headerText = s"File not found. Unable to lead dot file."
            contentText = s"File: $f"
          }.showAndWait()
        }
      }
    }

    /** Render the dot source into an image to display. */
    def render(): Unit = {
      val source = getDotSource().trim
      Platform.runLater {
        runner.run(source)
      }
    }

    var svgFile: Option[String] = None
    def reload(svg: Option[String] = None): Unit = {
      svg.foreach { f =>
        val path = Paths.get(f)
        println(s"Loading SVG: ${path.toAbsolutePath}")
        Platform.runLater {
          val eng = svgView.getEngine()
          eng.load("file:///" + path.toAbsolutePath())
        }
      }
      svgFile = svg
    }

    val extraArgs = new TextField() {
      editable = true      
    }
    runner.extraArgs <== extraArgs.text

    import org.fxmisc.richtext._
    import org.fxmisc.flowless._

    val editor = new CodeArea()
    editor.setParagraphGraphicFactory(LineNumberFactory.get(editor))

    //val editor = new TextArea() { editable = true }
    /** Get the dot source from the text editor. */
    def getDotSource(): String = editor.getDocument.getText
    def setDotSource(source: String) = editor.replaceText(0, 0, source)

    val slider = new Slider(0.1, 5, 0.25)
    slider.blockIncrement = 0.25f
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

    /**
     * A graphviz option.
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

    /** Create tables to capture graph, node or edge options. */
    def makeTable2(model: scalafx.collections.ObservableBuffer[AnOption] = new scalafx.collections.ObservableBuffer[AnOption]()) = {
      val v = new scalafx.scene.control.TableView[AnOption] {
        editable = true
        items = model
        columns ++= List(
          new TableColumn[AnOption, String] {
            text = "Key"
            prefWidth = 75
            cellValueFactory = { _.value.key }
            editable = true
            val x = TextFieldTableCell.forTableColumn[AnOption]
            cellFactory = x
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
      }
      v
    }

    val gtb = makeTable2()
    val ggrid = gtb.getItems
    val ntb = makeTable2()
    val ngrid = ntb.getItems
    val etb = makeTable2()
    val egrid = etb.getItems
    ggrid += new AnOption("layout", "fdp", true)

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
      children addAll (slider, extraArgs, runButton, inputs)
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
          messages.appendText(s"[$counter]: ${formatter.format(Instant.now)}\n")
          messages.appendText(text)
          messages.appendText("\n")
        }
      }
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
                  exitButton)
              },
              new Menu("_Edit") {
                items = List(
                  new MenuItem("Cut"),
                  new MenuItem("Copy"),
                  new MenuItem("Paste"))
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
