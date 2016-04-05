package jjsp.jde;

import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.logging.*;
import javax.script.*;

import javafx.application.*;
import javafx.event.*;
import javafx.scene.*;
import javafx.beans.*;
import javafx.beans.value.*;
import javafx.scene.paint.*;
import javafx.scene.text.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.input.*;
import javafx.scene.image.*;
import javafx.scene.web.*;

import javafx.geometry.*;
import javafx.util.*;
import javafx.stage.*;
import javafx.collections.*;

import jjsp.engine.*;
import jjsp.util.*;
import jjsp.http.*;
import jjsp.http.filters.*;

public class SourcePane extends JDETextEditor
{
    private SplitPane mainSplit;
    private BorderPane leftPane;
    private ColouredSearchableTextOutput jetEngineOutput;
    
    private int errorLine;
    private String argList;
    private String siteOutput;
    private String statusMessage;
    private Throwable currentError;
    
    private HTTPLogView log;
    private JDEEngine jetEngine;
    private LocalStoreView localStoreView;

    private FileChooser fileChooser;

    public SourcePane(URI initialURI, SharedTextEditorState sharedState)
    {
        super(initialURI, sharedState);
    }

    class ColouredSearchableTextOutput extends JDETextEditor
    {
        ColouredTextArea output;
            
        ColouredSearchableTextOutput()
        {
            super(null, null, false);
        }

        protected void init(SharedTextEditorState sharedState)
        {
            super.init(sharedState);
            
            editor = new ColouredTextArea(EDITOR_TEXT_SIZE);
            editor.setColours(Color.web("#007C29"));
            editor.setMinHeight(50);
            
            setCenter(editor);
            output = (ColouredTextArea) editor;
        }
    }
    
    protected void showSearchBox()
    {
        super.showSearchBox();
        jetEngineOutput.showSearchBox();
    }

    protected void hideSearchBox()
    {
        super.hideSearchBox();
        jetEngineOutput.hideSearchBox();
    }
    
    protected void init(SharedTextEditorState sharedState)
    {
        if (getURI().toString().endsWith(".jet"))
            editor = new JDEditor();
        else
            editor = new JSEditor();

        editor.setSharedTextEditorState(sharedState);
        editor.setMinHeight(100);
        log = new HTTPLogView();
        localStoreView = new LocalStoreView();

        errorLine = -1;
        jetEngine = null;
        currentError = null;
        statusMessage = "";
        siteOutput = "";
        argList = getDefaultArgs();

        jetEngineOutput = new ColouredSearchableTextOutput();

        leftPane = new BorderPane();
        leftPane.setCenter(editor);
        leftPane.setMinWidth(550);

        searchBox = new BorderPane();
        leftPane.setTop(searchBox);

        Tab tab1 = new Tab("Jet Output");
        tab1.setContent(jetEngineOutput);
        tab1.setClosable(false);
        Tab tab2 = new Tab("HTTP Log");
        tab2.setContent(log);
        tab2.setClosable(false);
        Tab tab3 = new Tab("Local Jet Store");
        tab3.setContent(localStoreView);
        tab3.setClosable(false);


        TabPane tabs = new TabPane();
        tabs.setSide(Side.BOTTOM);
        tabs.getTabs().addAll(tab1, tab2, tab3);
        tabs.setOnMousePressed((evt) -> 
                          {
                              Tab tt = tabs.getSelectionModel().getSelectedItem();
                              if (tt != null)
                                  ((Node) tt.getContent()).requestFocus();
                          });
        
        mainSplit = new SplitPane();
        mainSplit.setOrientation(Orientation.VERTICAL);
        mainSplit.getItems().addAll(leftPane, tabs);
        mainSplit.setDividerPosition(0, 0.66);
        setCenter(mainSplit);
        
        clearStatus();
        appendStatus("Ready: "+new Date(), null);
        loadFromURI();
    }

    protected FileChooser initFileChooser()
    {
        FileChooser fileChooser = new FileChooser();
        if (editor instanceof JDEditor)
            fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Jet Source Code", "*.jet"));
        else
            fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Jet Fuel Code", "*.jf"));
        return fileChooser;
    }

    public Menu[] createMenus()
    {
        MenuItem compile = new MenuItem("Compile + Run");
        compile.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        compile.setOnAction((evt)-> compile());
        MenuItem stop = new MenuItem("Stop Server");
        stop.setOnAction((evt)-> stop());

        MenuItem saveArchive = new MenuItem("Save Local Content in ZIP Archive");
        saveArchive.setOnAction((evt)-> 
                            { 
                                if ((jetEngine == null) || (jetEngine.getRuntime() == null))
                                {
                                    clearStatus();
                                    appendStatus("No Output available - compile first", null);
                                    return;
                                }

                                FileChooser fc1 = getFileChooser();
                                FileChooser zfc = new FileChooser();
                                zfc.setInitialDirectory(fc1.getInitialDirectory());
                                zfc.setTitle("Save Local Content as ZIP Archive");
                                zfc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("ZIP Archive", "*.zip"));
                                
                                File file = zfc.showSaveDialog(getScene().getWindow());
                                if (file != null)
                                {
                                    try
                                    {
                                        byte[] archiveBytes = jetEngine.getRuntime().toZIPArchive();
                                        FileOutputStream fout = new FileOutputStream(file);
                                        fout.write(archiveBytes);
                                        fout.close();
                                    }
                                    catch (Exception e) 
                                    {
                                        clearStatus();
                                        appendStatus("Error saving Local content as ZIP Output", e);
                                    }
                                }
                            });

        MenuItem clearHTTPLog = new MenuItem("Clear HTTP Log");
        clearHTTPLog.setOnAction((evt)-> log.clear());
        
        MenuItem extraArgs = new MenuItem("Runtime Args");
        extraArgs.setOnAction((evt)-> showArgsPopup());

        Menu[] mm = super.createMenus();
        mm[0].setText("Jet Actions");
        mm[0].getItems().addAll(new SeparatorMenuItem(), compile, stop, new SeparatorMenuItem(), extraArgs, new SeparatorMenuItem(), saveArchive, clearHTTPLog);   

        CheckMenuItem showTranslation = new CheckMenuItem("Show Jet Translation");
        showTranslation.setSelected(mainSplit.getItems().get(0) != leftPane);
        showTranslation.setOnAction((evt)-> 
                               { 
                                   double pos = mainSplit.getDividerPositions()[0];
                                   
                                   mainSplit.getItems().set(0, new BorderPane());
                                   if (showTranslation.isSelected()) 
                                   {
                                       SplitPane hSplit = new SplitPane();
                                       hSplit.setOrientation(Orientation.HORIZONTAL);
                                       hSplit.getItems().addAll(leftPane, ((JDEditor) editor).translatedJet);
                                       hSplit.setDividerPosition(0, 0.66);
                                       mainSplit.getItems().set(0, hSplit);
                                   } 
                                   else 
                                       mainSplit.getItems().set(0, leftPane);
                                   mainSplit.setDividerPosition(0, pos);
                               });

        MenuItem clearError = new MenuItem("Clear Error Status");
        clearError.setOnAction((evt)-> clearError());

        Menu display = new Menu("Further Options");
        if (editor instanceof JDEditor)
            display.getItems().addAll(clearError, showTranslation);
        else
            display.getItems().addAll(clearError);

        return new Menu[]{mm[0], display};
    }

    public Throwable getError()
    {
        return currentError;
    }

    public boolean isShowingError()
    {
        return currentError != null;
    }
    
    public void clearError() 
    {
        if (isShowingError())
        {
            closeServices();
            appendStatus("Error status cleared "+new Date(), null);
        }
    }

    public static int extractJSErrorLine(Throwable t)
    {
        for (Throwable tt = t; tt != null; tt = tt.getCause())
        {
            if (tt instanceof ScriptException)
                return ((ScriptException) tt).getLineNumber();
        
            int result = -1;
            StackTraceElement[] ss = tt.getStackTrace();
            for (int i=0; i<ss.length; i++)
            {
                StackTraceElement s = ss[i];
                if (!s.getClassName().startsWith("jdk.nashorn.internal.scripts.Script"))
                    continue;

                if (s.getFileName().startsWith("<eval>"))
                    result = s.getLineNumber();
                else if (s.getFileName().startsWith(JJSPRuntime.TOP_LEVEL_SOURCE_PATH))
                    result = s.getLineNumber();
            }
            
            if (result != -1)
                return result;
        }

        return -1;
    }

    public void clearStatus()
    {
        currentError = null;
        statusMessage = "";
        jetEngineOutput.output.setText("");
    }
    
    public void appendStatus(String message, Throwable t)
    {
        Throwable mainCause = t;
        for (Throwable tt = t; tt != null; tt = tt.getCause())
        {
            if (tt instanceof InvocationTargetException)
                mainCause = tt.getCause();
        }

        int line = extractJSErrorLine(t);
        if (line >= 0)
        {
            line = Math.max(0, line-1);
            double scrollPos = setErrorLine(line);
            editor.setScrollBarPosition(scrollPos);
            editor.highlightLine(line);
        }

        // This bit needed to drop multiple blank lines from the output
        String newStatus = statusMessage + message;
        String[] lines = newStatus.split("\n");

        boolean previousBlank = false;
        StringBuffer buf = new StringBuffer();
        for (int i=0; i<lines.length; i++)
        {
            String outLine = lines[i].trim();
            if (outLine.length() == 0)
            {
                if (previousBlank)
                    continue;
                previousBlank = true;
            }
            else
                previousBlank = false;

            buf.append(outLine+"\n");
        }
        
        statusMessage = buf.toString();
        //statusMessage += message; //an alternative if cleansing multiple blank lines isn't wanted
        currentError = mainCause;

        if (mainCause != null)
        {
            jetEngineOutput.output.setText("");
            jetEngineOutput.output.appendColouredText(statusMessage, Color.MAGENTA);
            jetEngineOutput.output.appendColouredText("\n\n"+toString(mainCause), Color.RED);
        }
        else
            jetEngineOutput.output.setText(statusMessage);

        jetEngineOutput.output.setScrollBarPosition(100.0);
        jetEngineOutput.output.refreshView();
    }

    public String getStatusText()
    {
        jetEngineOutput.output.refreshView();
        return jetEngineOutput.output.getText();
    }

    public void appendStatusText(String text)
    {
        if ((text == null) || (text.length() == 0))
            return;
        
        statusMessage += text;
        
        jetEngineOutput.output.setText("");
        jetEngineOutput.output.appendColouredText(statusMessage, Color.web("#660033"));
        jetEngineOutput.output.setScrollBarPosition(100.0);
        jetEngineOutput.output.refreshView();
    }

    public void requestFocus()
    {
        jetEngineOutput.output.refreshView();
        editor.refreshView();
        editor.requestFocus();
        super.requestFocus();
    }

    public double setErrorLine(int line)
    {
        errorLine = line;
        if (editor instanceof JDEditor)
            return ((JDEditor) editor).setErrorLine(line);
        return -1;
    }
        
    public double setSourceAndPosition(String jsSrc, int line, double scrollBarPos)
    {
        String editMessage = "<< Source edited since last compilation >>\n";
        String outText = jetEngineOutput.output.getText();
        if (!outText.startsWith(editMessage))
            jetEngineOutput.output.insertColouredText(0, editMessage, Color.BLUE);

        if (editor instanceof JDEditor)
            return ((JDEditor) editor).setSourceAndPosition(jsSrc, line, scrollBarPos);
        return -1;
    }

    public synchronized void closeServices()
    {
        setErrorLine(-1);
        generatedOutputs = null;
        localStoreView.setEnvironment(null);

        if (jetEngine != null)
            jetEngine.stop();

        setDisplayed(true);

        jetEngine = null;
        appendStatus(new Date()+" Jet Engine stopped", null);
        
        for (int i=0; i<5; i++)
        {
            System.gc();
            try
            {
                Thread.sleep(100);
            }
            catch (Exception e) {}
        }
    }

    public synchronized void stop()
    {
        closeServices();
        generatedOutputs = new URI[0];
    }

    class JDEEngine extends Engine
    {
        Throwable error;
        ArrayList resultURIs;

        public JDEEngine(String jsSrc, URI srcURI, Environment jetEnv, Map args)
        {
            super(jsSrc, srcURI, jetEnv.getRootURI(), jetEnv.getLocalCacheDir(), args);
            resultURIs = new ArrayList();
            error = null;
        }
        
        protected void compileJet(JJSPRuntime runtime, String jsSrc) throws Exception
        {
            System.gc();
            System.gc();
            
            Date startTime = new Date();
            println("Compilation Started "+startTime+"\nJet Version"+getVersion());
            println();
            super.compileJet(runtime, jsSrc);

            Date finishTime = new Date();
            long timeTaken = finishTime.getTime() - startTime.getTime();

            println("\n\nCompilation Completed OK "+finishTime);
            println("It took "+timeTaken+" ms.");
            println();
            
        }

        class JDELogger extends Logger
        {
            JDELogger()
            {
                super("JDE", null);
                setUseParentHandlers(false);
            }

            public void log(LogRecord lr)
            {
                println(new Date(lr.getMillis())+" "+lr.getLevel()+" "+lr.getSourceClassName()+"  "+lr.getSourceMethodName()+"  "+lr.getMessage());
            }
        }
        
        protected Logger getLogger()
        {
            return new JDELogger();
        }

        protected HTTPServerLogger getHTTPLog(JJSPRuntime runtime) throws Exception
        {
            HTTPServerLogger logger = runtime.getHTTPLogger();
            if (logger == null)
                return log;

            return (logEntry) -> {logger.requestProcessed(logEntry); log.requestProcessed(logEntry);}; 
        }

        protected ServerSocketInfo getDefaultServerSocket(JJSPRuntime runtime) throws Exception 
        {
            ServerSocketInfo result = super.getDefaultServerSocket(runtime);
            println("No socket defined in source - using "+result);
            return result;
        }

        protected void serverListening(HTTPServer server, ServerSocketInfo socketInfo, Exception listenError) throws Exception
        {
            URI serviceRoot = null;
            if (socketInfo.isSecure)
            {
                serviceRoot = new URI("https://localhost:"+socketInfo.port+"/");
                if (socketInfo.port == 443)
                    serviceRoot = new URI("https://localhost/");
            }
            else 
            {
                serviceRoot = new URI("http://localhost:"+socketInfo.port+"/");
                if (socketInfo.port == 80)
                    serviceRoot = new URI("http://localhost/");
            }
                
            println(new Date()+"  Server Listening on "+socketInfo);
            resultURIs.add(serviceRoot);

            if (editor instanceof JDEditor)
            {
                String[] allPaths = getRuntime().listLocal();
                for (int i=0; i<allPaths.length; i++)
                {
                    String path = allPaths[i];
                    path = JJSPRuntime.checkLocalResourcePath(path);
                    resultURIs.add(serviceRoot.resolve(path));
                }
            }
            else // else use a sitemap....
            {
                try
                {
                    URL sitemapURL = serviceRoot.resolve("/sitemap.xml").toURL();
                    URLConnection conn = sitemapURL.openConnection();
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
                    
                    String sitemapXML = Utils.loadText(conn.getInputStream());
                    //System.out.println(sitemapXML);

                    int pos = 0;
                    while (true)
                    {
                        int locIndex = sitemapXML.indexOf("<loc>", pos);
                        if (locIndex < 0)
                            break;
                        int locIndex2 = sitemapXML.indexOf("</loc>", locIndex);
                        if (locIndex2 < 0)
                            break;
                        pos = locIndex2 + 6;

                        String url = sitemapXML.substring(locIndex+5, locIndex2);
                        url = url.replace("\n", "");
                        url = url.replace("\r", "");
                        url = url.replace("\t", "");
                        url = url.replace(" ", "");
                        
                        URI uri = new URI(url);
                        uri = serviceRoot.resolve(uri.getPath());
                        resultURIs.add(uri);
                    }
                }
                catch (Exception e) 
                { 
                    println("No sitemap.xml found ("+e.getMessage()+")");
                }
            }
        }
        
        protected synchronized void runtimeError(Throwable t) 
        {
            println("\n\nJet Error: "+t.getMessage());
            error = t;
        }
        
        protected void launchComplete(HTTPServer server, JJSPRuntime runtime, boolean isListening) throws Exception
        {
            URI[] uris = new URI[resultURIs.size()];
            resultURIs.toArray(uris);
            generatedOutputs = uris;
            println(new Date()+" Jet Server Started");
            
            Platform.runLater(() -> localStoreView.setEnvironment(runtime));
        }

        protected void engineStopped() 
        {
            generatedOutputs = null;
            super.engineStopped();
        }
    }

    public String getCompiledJS()
    {
        if (editor instanceof JDEditor)
            return ((JDEditor) editor).getTranslatedText();
        else
            return editor.getText();
    }

    public void compile()
    {
        closeServices();
        clearStatus();
        log.clear();

        try
        {
            String jsSrc = getCompiledJS();
            Map args = Args.parseArgs(argList);
            
            synchronized (this)
            {
                jetEngine = new JDEEngine(jsSrc, getURI(), JDE.getEnvironment(), args);
                jetEngine.start();
            }
        }
        catch (Exception e) 
        {
            appendStatus("Failed to create Jet Engine", e);
        }
    }

    public synchronized void setDisplayed(boolean isShowing) 
    {
        super.setDisplayed(isShowing);

        JDEEngine je = jetEngine;
        if ((je != null) && je.stopRequested() && !je.stopped())
            je.stop();

        jetEngineOutput.output.setDisplayed(isShowing);
        editor.setDisplayed(isShowing);

        if (isShowing && (je != null))
        {
            String toAppend = je.getLatestConsoleOutput();
            if ((toAppend != null) && (toAppend.length() > 0) || (je.error != currentError))
                appendStatus(toAppend, je.error);
        }
    }

    class JDEditor extends Editor
    {
        TextEditor translatedJet;
        
        JDEditor()
        {
            super(EDITOR_TEXT_SIZE);
            setMinSize(400, 200);

            translatedJet = new TextEditor(EDITOR_TEXT_SIZE);
            translatedJet.setEditable(false);
            translateJet();
        }

        void translateJet()
        {
            String compiledJet = getJetParser().translateToJavascript();
            translatedJet.highlightLine(getCurrentLine());
            double scrollBarPos = getScrollBarPosition();
            setSourceAndPosition(compiledJet, getCurrentLine(), scrollBarPos);
        }
        
        public void requestFocus()
        {
            refreshView();
            super.requestFocus();
        }
        
        public void refreshView()
        {
            super.refreshView();
            if (translatedJet != null)
                translatedJet.refreshView();
        }

        public void setDisplayed(boolean isShowing) 
        {
            super.setDisplayed(isShowing);
            if (translatedJet != null)
                translatedJet.setIsShowing(isShowing);
        }

        protected void contentChanged() 
        {
            if (translatedJet != null)
                translateJet();
        }
        
        protected void caretPositioned(int line, int charPos) 
        {
            if (translatedJet != null)
                translatedJet.highlightLine(line);
        }

        protected void textScrolled(double scrollPosition) 
        {
            if (translatedJet != null)
                translatedJet.setScrollBarPosition(scrollPosition);
        }

        double setErrorLine(int errorLine)
        {
            if (errorLine >= 0)
            {
                translatedJet.highlightLine(errorLine);
                int lineStatus = translatedJet.lineInViewport(errorLine);
                if (lineStatus != 0)
                    translatedJet.scrollToLine(errorLine);
            }
            else
                translatedJet.clearSelection();

            return translatedJet.getScrollBarPosition();
        }

        double setSourceAndPosition(String jsSrc, int line, double scrollBarPos)
        {
            translatedJet.setText(jsSrc);
            translatedJet.setScrollBarPosition(scrollBarPos);
            translatedJet.highlightLine(line);
            
            int lineStatus = translatedJet.lineInViewport(line);
            if (lineStatus != 0)
                translatedJet.scrollToLine(line);

            return translatedJet.getScrollBarPosition();
        }

        public String getTranslatedText()
        {
            translateJet();
            return translatedJet.getText();
        }
    }

    private String getDefaultArgs()
    {
        Environment jetEnv = JDE.getEnvironment();
        Map args = jetEnv.getArgs();
        args.put("jde", "true");
        
        return Args.toArgString(args);
    }

    private void showArgsPopup()
    {
        Stage dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        Button ok = new Button("OK");
        
        TextField argField = new TextField(argList);
        argField.setOnAction((evt) -> ok.fire());
        argField.setPrefWidth(350);

        Button reset = new Button("Reset");
        reset.setOnAction((evt) -> { argList = getDefaultArgs(); argField.setText(argList); });
        
        GridPane gp = new GridPane();
        gp.setHgap(10);
        gp.setVgap(10);
            
        gp.add(new Label("Define Jet Args"), 0, 0);
        gp.add(argField, 1, 0);

        ok.setOnAction((evt) -> 
                       { 
                           argList = argField.getText();
                           dialogStage.close(); 
                       });

        Button cancel = new Button("Cancel");
        cancel.setOnAction((evt) -> dialogStage.close());

        HBox hBox = new HBox(10);
        BorderPane.setMargin(hBox, new Insets(10,0,0,0));
        hBox.setAlignment(Pos.BASELINE_CENTER);
        hBox.getChildren().addAll(reset, cancel, ok);
            
        BorderPane bp = new BorderPane();
        bp.setStyle("-fx-font-size: 16px; -fx-padding:10px");
        bp.setCenter(gp);
        bp.setBottom(hBox);
            
        dialogStage.setTitle("Jet Runtime Arguments");
        dialogStage.setScene(new Scene(bp));
        if (ImageIconCache.getJetImage() != null)
            dialogStage.getIcons().add(ImageIconCache.getJetImage());
        dialogStage.sizeToScene();
        dialogStage.setResizable(false);
        dialogStage.showAndWait();
    }
}
