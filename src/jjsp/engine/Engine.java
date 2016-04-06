package jjsp.engine;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;
import java.awt.image.*;
import java.lang.reflect.*;

import javax.imageio.*;
import javax.script.*;

import jdk.nashorn.api.scripting.*;

import jjsp.http.*;
import jjsp.http.filters.*;
import jjsp.util.*;

public class Engine implements Runnable
{
    private final Map args;
    private final String jsSrc;
    private final File localCacheDir;
    private final URI rootURI, sourceURI;

    private HTTPServer server;
    private JJSPRuntime jetRuntime;
    private boolean started, stop;

    public Engine(String jsSrc, URI sourceURI, URI rootURI, File localCacheDir, Map args)
    {
        this.jsSrc = jsSrc;
        this.rootURI = rootURI;
        this.sourceURI = sourceURI;
        this.localCacheDir = localCacheDir;
        this.args = args;
        
        started = stop = false;
    }

    public synchronized void start()
    {
        if (started)
            throw new IllegalStateException("Engine already started");
        started = true;
        new Thread(this).start();
    }
    
    public synchronized boolean started()
    {
        return started;
    }

    public boolean stopRequested()
    {
        JJSPRuntime jr = jetRuntime;
        if (jr == null)
            return false;
        return jr.stopRequested();
    }

    public void stop()
    {
        synchronized (this)
        {
            stop = true;
            notifyAll();
        }

        try
        {
            server.close();
        }
        catch (Throwable e){}

        try
        {
            if (jetRuntime != null)
                jetRuntime.engineStopped();
        }
        catch (Throwable t)
        {
            runtimeError(t);
        }

        try
        {
            engineStopped();
        }
        catch (Throwable t)
        {
            runtimeError(t);
        }
    }

    public synchronized boolean stopped()
    {
        return stop;
    }

    public URI getSourceURI()
    {
        return sourceURI;
    }

    public URI getRootURI()
    {
        return rootURI;
    }

    public synchronized JJSPRuntime getRuntime()
    {
        return jetRuntime;
    }

    public void print(String s)
    {
        JJSPRuntime jr = getRuntime();
        if (jr == null)
            return;
        jr.print(s);
    }

    public void println()
    {
        println("");
    }
        
    public void println(Object obj)
    {
        JJSPRuntime jr = getRuntime();
        if (jr == null)
            return;
        jr.println(obj);
    }

    public void printStackTrace(Throwable t)
    {
        JJSPRuntime rt = getRuntime();
        if (rt == null)
            return;
        rt.printStackTrace(t);
    }

    public void log(Level level, Object msg)
    {
        JJSPRuntime rt = getRuntime();
        if (rt == null)
            return;
        rt.log(level, msg);
    }

    protected void compileJet(JJSPRuntime runtime, String jsSrc) throws Exception
    {
        getRuntime().init(jsSrc);
    }

    protected Logger getLogger()
    {
        return Logger.getGlobal();
    }

    protected HTTPServerLogger getHTTPLog(JJSPRuntime runtime) throws Exception
    {
        return runtime.getHTTPLogger();
    }

    protected synchronized HTTPServer createServer(JJSPRuntime runtime) throws Exception
    {
        if (stop)
            return null;

        HTTPRequestFilter mainFilter = jetRuntime.getMainRequestFilter();
        if (mainFilter == null)
            return null;

        server = new HTTPServer(mainFilter, getHTTPLog(jetRuntime));
        return server;
    }

    protected ServerSocketInfo getDefaultServerSocket(JJSPRuntime runtime) throws Exception 
    {
        int port = Utils.getFreeSocket(null, JJSPRuntime.DEFAULT_PORT_BASE, JJSPRuntime.DEFAULT_PORT_BASE+128); 
        if (port < 0)
            throw new IOException("No free port available for service (checked "+JJSPRuntime.DEFAULT_PORT_BASE+"..."+(JJSPRuntime.DEFAULT_PORT_BASE+128)+")");
        return new ServerSocketInfo(port, false, null);
    }

    protected void serverListening(HTTPServer server, ServerSocketInfo socketInfo, Exception listenError) throws Exception {}

    protected void launchComplete(HTTPServer server, JJSPRuntime runtime, boolean isListening) throws Exception 
    {
        if (isListening)
            getRuntime().println("Engine Running (Listening) "+new Date());
    }

    protected void runtimeError(Throwable t) {}

    protected void engineStopped() 
    {
        JJSPRuntime rt = getRuntime();
        if (rt == null)
            return;
        rt.println("Engine Runtime Stopped at "+new Date());
    }

    public String getLatestConsoleOutput() 
    {
        JJSPRuntime rt = getRuntime();
        if (rt == null)
            return "";
        return rt.getAndClearJetOutput();
    }
    
    public void run()
    {
        boolean launchOK = false;
        try
        {
            JJSPRuntime jr = new JJSPRuntime(rootURI, localCacheDir, args);
            jr.addResourcePathRoot(sourceURI);
            jr.setLogger(getLogger());

            synchronized (this)
            {
                jetRuntime = jr;
            }

            compileJet(jr, jsSrc);
            if (createServer(jr) == null)
            {
                launchComplete(null, jetRuntime, false);
                return;
            }

            ServerSocketInfo[] ssInfo = jr.getServerSockets();
            if ((ssInfo == null) || (ssInfo.length == 0))
                ssInfo = new ServerSocketInfo[]{getDefaultServerSocket(jr)};

            boolean isListening = false;
            for (int p=0; p<ssInfo.length; p++)
            {
                Exception listenError = null;
                ServerSocketInfo info = ssInfo[p];
                    
                for (int i=0; i<5; i++)
                {
                    listenError = null;
                    try
                    {
                        server.listenOn(info.port, info.isSecure, info.ipAddress);
                        isListening = true;
                        break;
                    }
                    catch (Exception e) 
                    {
                        listenError = e;
                    }

                    try
                    {
                        Thread.sleep(100);
                    }
                    catch (Exception e) {}
                }
                    
                serverListening(server, info, listenError);
            }

            launchComplete(server, jetRuntime, isListening);
            if (!isListening)
                stop();
            launchOK = true;
        }
        catch (Throwable t)
        {
            runtimeError(t);
        }
        finally
        {
            if (!launchOK)
                stop();
        }
    }

    static class DefaultEngine extends Engine
    {
        private long lastTimePrintout;

        DefaultEngine(String jsSrc, File srcFile, File rootDir, File cacheDir, Map jetArgs) 
        {
            super(jsSrc, srcFile.toURI(), rootDir.toURI(), cacheDir, jetArgs);
        }

        protected void compileJet(JJSPRuntime runtime, String jsSrc) throws Exception
        {
            log(Level.INFO, "Compiling JET Source from "+getSourceURI());
            super.compileJet(runtime, jsSrc);
            log(Level.INFO, "Compilation Complete");
        }

        protected HTTPServer createServer(JJSPRuntime runtime) throws Exception
        {
            log(Level.INFO, "Creating Server");
            return super.createServer(runtime);
        }

        protected void runtimeError(Throwable t)
        {
            log(Level.SEVERE, "JET Server Runtime Error");
            printStackTrace(t);
        }
        
        protected HTTPServerLogger getHTTPLog(JJSPRuntime runtime) throws Exception
        {
            HTTPServerLogger logger = runtime.getHTTPLogger();
            if (logger != null)
                return logger;
            return new PrintStreamLogger();
        }

        protected void serverListening(HTTPServer server, ServerSocketInfo socketInfo, Exception listenError) throws Exception 
        {
            if (listenError != null)
            {
                log(Level.SEVERE, "Error listening on "+socketInfo);
                throw listenError;
            }
            else
                log(Level.INFO, "Server listening on "+socketInfo);
        }

        protected void launchComplete(HTTPServer server, JJSPRuntime runtime, boolean isListening) throws Exception 
        {
            if (!isListening)
                throw new IOException("FATAL: No port opened to listen on");
            log(Level.INFO, "Servier Started");
        }
    }
    
    public static void main(String[] args) throws Exception
    {
        Map jetArgs = Args.parse(args);
        String fileName = Args.getArg("src", null);

        if ((args.length == 0) || (fileName == null))
        {
            System.out.println("Usage: -src <source file> [-root <root dir>] [-cache <Jet Cache Directory>] [...other args]");
            System.out.println();
            System.out.println("        src file : The main Jet source file name (required)");
            System.out.println("        root     : The directory name of the Jet root, defaults to the current working directory.");
            System.out.println("        cache    : The directory name of the Jet file cache directory, defaults to 'jetcache' in the process working directory.");
            System.out.println("        logDir   : The log directory name relative to the current working directory (defaults to 'logs')");
            System.out.println("   ");
            System.out.println("   Other arguments are allowed and are passed on to the JJSPRuntime");
            System.out.println("   NOTE: if not already specified, an additional option 'mode = production' is automatically added");
            System.out.println();
            if (fileName == null)
                throw new NullPointerException("No Jet Source file specified; use -src option");
            return;
        }
    
        String cwd = System.getProperty("user.dir");
        String rootDirName = Args.getArg("root", cwd);
        String cacheDirName = Args.getArg("cache", new File(cwd, Environment.CACHE_DIR).getAbsolutePath());
        String logDir = Args.getArg("logDir", new File(cwd, "logs").getAbsolutePath());

        System.out.println("\nLogging output to log directory "+logDir+"\n");
        Log.set(logDir);
        Logger log = Logger.getGlobal();

        jetArgs.remove("src");
        jetArgs.remove("root");
        jetArgs.remove("cache");
        jetArgs.remove("logDir");
        if (jetArgs.get("mode") == null)
            jetArgs.put("mode", "production");
        
        //System.out.println(jetArgs);
        File srcFile = new File(fileName);
        if (!srcFile.exists() || !srcFile.isFile())
            throw new IllegalStateException("Source file '"+srcFile+"' not found");
        
        File rootDir = new File(rootDirName);
        if (!rootDir.exists() || !rootDir.isDirectory())
            throw new IllegalStateException("Root directory '"+rootDir+"' not found");
        File cacheDir = new File(cacheDirName);
        if (!cacheDir.exists() || !cacheDir.isDirectory())
            cacheDir.mkdirs();

        String jsSrc = Utils.loadText(srcFile);
        if (srcFile.getName().endsWith(".jet"))
            jsSrc = new ScriptParser(jsSrc).translateToJavascript();

        Engine engine = new DefaultEngine(jsSrc, srcFile, rootDir, cacheDir, jetArgs);
        engine.start();
        
        while (true)
        {
            String output = engine.getLatestConsoleOutput();
            if ((output != null) && (output.length()>0) && (log != null))
                log.log(Level.INFO, output);

            if (engine.stopped())
                break;
            if (engine.stopRequested())
                engine.stop();
            try
            {
                Thread.sleep(200);
            }
            catch (Exception e) {}
        }

        String output = engine.getLatestConsoleOutput();
        if (log != null)
        {
            log.log(Level.INFO, output);
            log.log(Level.INFO, "JET Process Exit");
        }

        try
        {
            Thread.sleep(1000);
        }
        catch (Throwable t) {}
        System.exit(0);
    }
}