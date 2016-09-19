package act.cli;

import act.app.ActionContext;
import act.app.App;
import act.cli.ascii_table.ASCIITableHeader;
import act.cli.ascii_table.impl.SimpleASCIITableImpl;
import act.cli.ascii_table.spec.IASCIITable;
import act.cli.ascii_table.spec.IASCIITableAware;
import act.cli.builtin.Exit;
import act.cli.builtin.Help;
import act.cli.util.CommandLineParser;
import act.handler.CliHandler;
import act.util.ActContext;
import jline.console.ConsoleReader;
import org.osgl.$;
import org.osgl.cache.CacheService;
import org.osgl.concurrent.ContextLocal;
import org.osgl.http.H;
import org.osgl.util.C;
import org.osgl.util.E;
import org.osgl.util.S;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CliContext extends ActContext.Base<CliContext> implements IASCIITable {

    public static class ParsingContext {

        // The number of options plus arguments in
        // the command executor method params.
        // This does not include the provided params (e.g. those
        // params that should be injected by framework, like. App etc)
        private int optionArgumentsCnt;

        // mark the current loading argument
        private AtomicInteger curArgId;

        // Keep track the number of options provided
        // for a specific required group
        Map<String, AtomicInteger> required;

        private ParsingContext() {}

        public AtomicInteger curArgId() {
            return curArgId;
        }

        public boolean hasArguments(CommandLineParser command) {
            return curArgId.get() < command.argumentCount();
        }

        public void foundRequired(String group) {
            required.get(group).incrementAndGet();
        }

        public boolean hasMultipleOptionArguments() {
            return optionArgumentsCnt > 1;
        }

        public Set<String> missingOptions() {
            Set<String> set = new HashSet<>();
            for (Map.Entry<String, AtomicInteger> entry : required.entrySet()) {
                if (entry.getValue().get() < 1) {
                    set.add(entry.getKey());
                }
            }
            return set;
        }

        public void raiseExceptionIfThereAreMissingOptions() {
            Set<String> missings = missingOptions();
            if (!missings.isEmpty()) {
                throw new CliException("Missing required options: %s", missings);
            }
        }

        public ParsingContext copy() {
            ParsingContext ctx = new ParsingContext();
            ctx.optionArgumentsCnt = optionArgumentsCnt;
            ctx.required = new HashMap<>(required);
            for (Map.Entry<String, AtomicInteger> entry : ctx.required.entrySet()) {
                entry.setValue(new AtomicInteger(0));
            }
            ctx.curArgId = new AtomicInteger(0);
            return ctx;
        }
    }

    public static class ParsingContextBuilder {
        private static final ThreadLocal<ParsingContext> ctx = new ThreadLocal<>();

        public static void start() {
            ParsingContext ctx0 = new ParsingContext();
            ctx0.required = new HashMap<>();
            ctx.set(ctx0);
        }

        public static void foundOptional() {
            ctx.get().optionArgumentsCnt++;
        }

        public static void foundArgument() {
            ctx.get().optionArgumentsCnt++;
        }

        public static void foundRequired(String group) {
            ParsingContext ctx0 = ctx.get();
            ctx0.optionArgumentsCnt++;
            ctx0.required.put(group, new AtomicInteger(0));
        }

        public static ParsingContext finish() {
            ParsingContext ctx0 = ctx.get();
            ctx.remove();
            return ctx0;
        }

    }

    public static final String ATTR_PWD = "__act_pwd__";
    public static final String ATTR_METHOD = "__act_method__";

    private static final ContextLocal<CliContext> _local = $.contextLocal();

    private CliSession session;

    private String commandPath; // e.g. myapp.cli.ListUser

    private Map<String, Object> commanderInstances = C.newMap();

    private ConsoleReader console;

    // workaround of issue http://stackoverflow.com/questions/34467383/jline2-print-j-when-it-should-print-n-on-a-telnet-console
    private PrintWriter pw;

    private CommandLineParser parser;

    private IASCIITable asciiTable;

    private CacheService evaluatorCache;

    private ParsingContext parsingContext;

    private CliHandler handler;

    private boolean rawPrint;

    private Map<String, String> preparsedOptionValues;

    public CliContext(String line, App app, ConsoleReader console, CliSession session) {
        this(line, app, console, session, null == System.getenv("cli-no-raw-print"));
    }

    protected CliContext(String line, App app, ConsoleReader console, CliSession session, boolean rawPrint) {
        super(app);
        this.session = session;
        this.parser = new CommandLineParser(line);
        this.evaluatorCache = app.cache();
        this.console = $.NPE(console);
        this.pw = new PrintWriter(console.getOutput());
        this.rawPrint = rawPrint;
        this.handler = app.cliDispatcher().handler(command());
        this.preparsedOptionValues = new HashMap<>();
        this.saveLocal();
    }

    /**
     * Set the console prompt
     * @param prompt the prompt
     */
    public void prompt(String prompt) {
        console.setPrompt(prompt);
    }

    public void prepare(ParsingContext ctx) {
        this.parsingContext = ctx.copy();
    }

    public ParsingContext parsingContext() {
        return this.parsingContext;
    }

    /**
     * Reset the console prompt to "{@code act[<session-id>]>}"
     */
    public void resetPrompt() {
        prompt("act[" + session.id() + "]>");
    }

    public CacheService evaluatorCache() {
        return evaluatorCache;
    }

    public CommandLineParser commandLine() {
        return parser;
    }

    public String command() {
        return parser.command();
    }

    public List<String> arguments() {
        return parser.arguments();
    }

    public CliSession session() {
        return session;
    }

    @Override
    public Set<String> paramKeys() {
        return preparsedOptionValues.keySet();
    }

    public void param(String key, String val) {
        this.preparsedOptionValues.put(key, val);
    }

    @Override
    public String paramVal(String key) {
        return this.preparsedOptionValues.get(key);
    }

    @Override
    public String[] paramVals(String key) {
        return new String[]{paramVal(key)};
    }

    /**
     * Return the current working directory
     * @return the current working directory
     */
    public File curDir() {
        File file = attribute(ATTR_PWD);
        if (null == file) {
            file = new File(System.getProperty("user.dir"));
            attribute(ATTR_PWD, file);
        }
        return file;
    }

    public CliContext chDir(File dir) {
        E.illegalArgumentIf(!dir.isDirectory());
        attribute(ATTR_PWD, dir);
        return this;
    }

    @Override
    public CliContext accept(H.Format fmt) {
        throw E.unsupport();
    }

    @Override
    public H.Format accept() {
        throw E.unsupport();
    }

    public void flush() {
        pw.flush();
    }

    public void print(String template, Object ... args) {
        if (rawPrint) {
            print1(template, args);
        } else {
            print0(template, args);
        }
    }

    /**
     * Run handler and return `false` if it needs to exit the CLI or `true` otherwise
     */
    void handle() throws IOException {
        if (null == handler) {
            println("Command not recognized: %s", command());
            return;
        }
        if (handler == Exit.INSTANCE) {
            handler.handle(this);
        }
        CommandLineParser parser = commandLine();
        boolean help = parser.getBoolean("-h", "--help");
        if (help) {
            Help.INSTANCE.showHelp(parser.command(), this);
        } else {
            try {
                handler.handle(this);
            } catch ($.Break b) {
                throw b;
            } catch (Exception e) {
                console.println("Error: " + e.getMessage());
            }
        }
    }

    private void print0(String template, Object... args) {
        try {
            console.print(S.fmt(template, args));
        } catch (IOException e) {
            throw E.ioException(e);
        }
    }

    private void print1(String template, Object ... args) {
        if (args.length == 0) {
            pw.print(template);
        } else {
            pw.printf(osNative(template), args);
        }
    }

    private void println0(String template, Object... args) {
        try {
            if (args.length > 0) {
                template = S.fmt(template);
            }
            console.println(template);
        } catch (IOException e) {
            throw E.ioException(e);
        }
    }

    public void println(String template, Object... args) {
        if (rawPrint) {
            println1(template, args);
        } else {
            println0(template, args);
        }
    }

    private void println1(String template, Object... args) {
        if (args.length == 0) {
            pw.print(template);
        } else {
            pw.printf(osNative(template), args);
        }
        pw.println();
    }

    @Override
    protected void releaseResources() {
        super.releaseResources();
        _local.remove();
    }

    public String commandPath() {
        return commandPath;
    }

    public CliContext commandPath(String path) {
        commandPath = path;
        return this;
    }

    public CliContext __commanderInstance(String className, Object instance) {
        if (null == commanderInstances) {
            commanderInstances = C.newMap();
        }
        commanderInstances.put(className, instance);
        return this;
    }

    public Object __commanderInstance(String className) {
        return null == commanderInstances ? null : commanderInstances.get(className);
    }

    /**
     * If {@link #templatePath(String) template path has been set before} then return
     * the template path. Otherwise returns the {@link #commandPath()}
     * @return either template path or action path if template path not set before
     */
    public String templatePath() {
        String path = super.templatePath();
        if (S.notBlank(path)) {
            return path;
        } else {
            return commandPath().replace('.', '/');
        }
    }

    @Override
    public CliContext templatePath(String templatePath) {
        return super.templatePath(templatePath);
    }

    @Override
    public <T> T renderArg(String name) {
        return super.renderArg(name);
    }

    @Override
    public CliContext renderArg(String name, Object val) {
        return super.renderArg(name, val);
    }

    @Override
    public Map<String, Object> renderArgs() {
        return super.renderArgs();
    }

    /**
     * Called by bytecode enhancer to set the name list of the render arguments that is update
     * by the enhancer
     * @param names the render argument names separated by ","
     * @return this AppContext
     */
    @SuppressWarnings("unused")
    public CliContext __appRenderArgNames(String names) {
        return renderArg("__arg_names__", C.listOf(names.split(",")));
    }

    @SuppressWarnings("unused")
    public List<String> __appRenderArgNames() {
        return renderArg("__arg_names__");
    }

    private synchronized IASCIITable tbl() {
        if (asciiTable == null) {
            asciiTable = new SimpleASCIITableImpl(new PrintWriter(console.getOutput()));
        }
        return asciiTable;
    }

    @Override
    public void printTable(String[] header, String[][] data) {
        tbl().printTable(header, data);
    }

    @Override
    public void printTable(String[] header, String[][] data, int dataAlign) {
        tbl().printTable(header, data, dataAlign);
    }

    @Override
    public void printTable(String[] header, int headerAlign, String[][] data, int dataAlign) {
        tbl().printTable(header, headerAlign, data, dataAlign);
    }

    @Override
    public void printTable(ASCIITableHeader[] headerObjs, String[][] data) {
        tbl().printTable(headerObjs, data);
    }

    @Override
    public void printTable(IASCIITableAware asciiTableAware) {
        tbl().printTable(asciiTableAware);
    }

    @Override
    public String getTable(String[] header, String[][] data) {
        return tbl().getTable(header, data);
    }

    @Override
    public String getTable(String[] header, String[][] data, int dataAlign) {
        return tbl().getTable(header, data, dataAlign);
    }

    @Override
    public String getTable(String[] header, int headerAlign, String[][] data, int dataAlign) {
        return tbl().getTable(header, headerAlign, data, dataAlign);
    }

    @Override
    public String getTable(ASCIITableHeader[] headerObjs, String[][] data) {
        return tbl().getTable(headerObjs, data);
    }

    @Override
    public String getTable(IASCIITableAware asciiTableAware) {
        return tbl().getTable(asciiTableAware);
    }

    public File getFile(String path) {
        File file = new File(path);
        if (file.isAbsolute()) {
            return file;
        }
        file = new File(curDir(), path);
        return new File(file.getAbsolutePath());
    }

    private void saveLocal() {
        _local.set(this);
    }

    private void initOverHttp(ActionContext actionContext) {

    }

    public static CliContext current() {
        return _local.get();
    }

    private static String osNative(String s) {
        s = s.replace("\n\r", "\n");
        s = s.replace("\r", "\n");
        if ("\n".equals($.OS.lineSeparator())) {
            return s;
        }
        s = s.replace("\n", $.OS.lineSeparator());
        return s;
    }


}