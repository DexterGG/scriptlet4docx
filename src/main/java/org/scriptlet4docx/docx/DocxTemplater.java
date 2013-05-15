package org.scriptlet4docx.docx;

import groovy.text.GStringTemplateEngine;
import groovy.util.AntBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.control.CompilationFailedException;
import org.scriptlet4docx.docx.Placeholder.ScriptWraps;
import org.scriptlet4docx.util.string.StringUtil;
import org.scriptlet4docx.util.xml.XMLUtils;

public class DocxTemplater {

    static final String PATH_TO_CONTENT = "word/document.xml";

    private File pathToDocx;
    private InputStream templateStream;
    private String streamTemplateKey;
    static final TemplateFileManager templateFileManager = new TemplateFileManager();

    /**
     * File-based constructor
     */
    public DocxTemplater(File pathToDocx) {
        this.pathToDocx = pathToDocx;
    }

    public DocxTemplater(InputStream inputStream, String templateKey) {
        this.templateStream = inputStream;
        this.streamTemplateKey = templateKey;
    }

    private static Pattern scriptPattern = Pattern.compile("((&lt;%=?(.*?)%&gt;)|\\$\\{(.*?)\\})", Pattern.DOTALL
            | Pattern.MULTILINE);

    static String cleanupTemplate(String template) {
        template = DividedScriptWrapsProcessor.process(template);
        template = TableScriptingProcessor.process(template);
        return template;
    }

    static String processCleanedTemplate(String template, Map<String, ? extends Object> params)
            throws CompilationFailedException, ClassNotFoundException, IOException {
        final String methodName = "processScriptedTemplate";

        String replacement = UUID.randomUUID().toString();

        List<Placeholder> scripts = new ArrayList<Placeholder>();

        Matcher m = scriptPattern.matcher(template);

        while (m.find()) {
            String scriptText = m.group(0);
            Placeholder ph = new Placeholder(UUID.randomUUID().toString(), scriptText, Placeholder.SCRIPT);

            if (ph.scriptWrap == ScriptWraps.DOLLAR_PRINT) {
                ph.setScriptTextNoWrap(m.group(4));
            } else if (ph.scriptWrap == ScriptWraps.SCRIPLET || ph.scriptWrap == ScriptWraps.SCRIPLET_PRINT) {
                ph.setScriptTextNoWrap(m.group(3));
            }

            scripts.add(ph);
        }

        String replacedScriptsTemplate = m.replaceAll(replacement);

        String[] pieces = StringUtils.splitByWholeSeparator(replacedScriptsTemplate, replacement);

        List<Placeholder> tplSkeleton = new ArrayList<Placeholder>();

        int i = 0;
        for (String piece : pieces) {
            tplSkeleton.add(new Placeholder(UUID.randomUUID().toString(), piece, Placeholder.TEXT));

            if (i < scripts.size()) {
                tplSkeleton.add(scripts.get(i));
            }
            i++;
        }

        StringBuilder builder = new StringBuilder();

        for (Placeholder placeholder : tplSkeleton) {
            if (Placeholder.SCRIPT == placeholder.type) {
                String cleanScriptNoWrap = XMLUtils.getNoTagsTrimText(placeholder.getScriptTextNoWrap());
                String script = placeholder.constructWithCurrentScriptWrap(cleanScriptNoWrap);
                builder.append(script);
            } else {
                builder.append(placeholder.ph);
            }
        }

        template = builder.toString();

        if (logger.isLoggable(Level.FINEST)) {
            logger.logp(Level.FINEST, CLASS_NAME, methodName, String.format("\ntemplate = \n%s\n", template));
        }

        GStringTemplateEngine engine1 = new GStringTemplateEngine();
        String scriptAppliedStr;
        try {
            scriptAppliedStr = String.valueOf(engine1.createTemplate(template).make(params));
        } catch (Throwable e) {
            logger.logp(Level.SEVERE, CLASS_NAME, methodName,
                    String.format("Cannot process template: [%s].", template), e);
            throw new RuntimeException(e);
        }

        scriptAppliedStr = StringUtil.escapeSimpleSet(scriptAppliedStr);

        String result = scriptAppliedStr;
        for (Placeholder placeholder : tplSkeleton) {
            if (Placeholder.TEXT == placeholder.type) {
                result = StringUtils.replace(result, placeholder.ph, placeholder.text);
            }
        }

        return result;
    }

    static String CLASS_NAME = DocxTemplater.class.getCanonicalName();
    static Logger logger = Logger.getLogger(CLASS_NAME);

    private String setupTemplate() throws IOException {
        String templateKey = null;
        if (pathToDocx != null) {
            // this is file-base usage
            // TODO what if hash collision? A longer hach algorythm may be
            // needed.
            templateKey = pathToDocx.hashCode() + "-" + FilenameUtils.getBaseName(pathToDocx.getName());
            templateFileManager.prepare(pathToDocx, templateKey);
        } else {
            // this is stream-based usage
            try {
                templateKey = streamTemplateKey;
                if (!templateFileManager.isTemplateFileFromStreamExists(templateKey)) {
                    templateFileManager.saveTemplateFileFromStream(templateKey, templateStream);
                    templateFileManager
                            .prepare(templateFileManager.getTemplateFileFromStream(templateKey), templateKey);
                }
            } finally {
                IOUtils.closeQuietly(templateStream);
            }

        }
        return templateKey;
    }

    public void process(File destDocx, Map<String, Object> params) {
        try {
            String templateKey = setupTemplate();

            String template = templateFileManager.getTemplateContent(templateKey);

            if (!templateFileManager.isPreProcessedTemplateExists(templateKey)) {
                template = cleanupTemplate(template);
                templateFileManager.savePreProcessed(templateKey, template);
            }

            String result = processCleanedTemplate(template, params);

            File tmpProcessFolder = templateFileManager.createTmpProcessFolder();

            destDocx.delete();
            FileUtils.deleteDirectory(tmpProcessFolder);

            FileUtils.copyDirectory(templateFileManager.getTemplateUnzipFolder(templateKey), tmpProcessFolder);

            FileUtils.writeStringToFile(new File(tmpProcessFolder, PATH_TO_CONTENT), result, "UTF-8");

            AntBuilder antBuilder = new AntBuilder();
            HashMap<String, Object> params1 = new HashMap<String, Object>();
            params1.put("destfile", destDocx);
            params1.put("basedir", tmpProcessFolder);
            params1.put("includes", "**/*.*");
            params1.put("excludes", "");
            params1.put("encoding", "UTF-8");
            antBuilder.invokeMethod("zip", params1);

            FileUtils.deleteDirectory(tmpProcessFolder);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public void cleanup(File file) {
        try {
            FileUtils.deleteDirectory(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Cleans up templater temporary folder.<br/>
     * Normally should be called when application is about to end its execution.
     */
    public static void cleanup() {
        try {
            templateFileManager.cleanup();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
