/*
 * ***** BEGIN LICENSE BLOCK *****
 * 
 * Zimbra Collaboration Suite Web Client
 * Copyright (C) 2006, 2007 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Yahoo! Public License
 * Version 1.0 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * 
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.webClient.servlet;

import com.yahoo.platform.yui.compressor.CssCompressor;
import com.yahoo.platform.yui.compressor.JavaScriptCompressor;
import com.zimbra.common.util.StringUtil;
import com.zimbra.common.util.ZimbraLog;
import org.mozilla.javascript.ErrorReporter;
import org.mozilla.javascript.EvaluatorException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.*;
import javax.servlet.http.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.*;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Provisioning.DomainBy;
import com.zimbra.kabuki.util.Colors;

import java.awt.Color;

/**
 * TODO: Clean up this code!
 */
public class SkinResources
        extends HttpServlet {

    //
    // Constants
    //

    private static final String P_SKIN = "skin";
	private static final String P_DEFAULT_SKIN = "zimbraDefaultSkin";
    private static final String P_USER_AGENT = "agent";
    private static final String P_DEBUG = "debug";
    private static final String P_CLIENT = "client";
	private static final String P_LOCALE = "locale";
	private static final String P_LANGUAGE = "language";
	private static final String P_COUNTRY = "country";
	private static final String P_VARIANT = "variant";
	private static final String P_SERVLET_PATH = "servlet-path";
	private static final String P_TEMPLATES = "templates";
	private static final String P_COMPRESS = "compress";

	private static final String V_TRUE = "true";
	private static final String V_FALSE = "false";
	private static final String V_SPLIT = "split";
	private static final String V_ONLY = "only";

	private static final long MAX_INCLUDED_TEMPLATES_SIZE = 1 << 13; // 8K

	private static final String A_TEMPLATES_INCLUDED = "skin.templates.included";

	private static final String A_SKIN_FOREGROUND_COLOR = "zimbraSkinForegroundColor";
	private static final String A_SKIN_BACKGROUND_COLOR = "zimbraSkinBackgroundColor";
	private static final String A_SKIN_SECONDARY_COLOR = "zimbraSkinSecondaryColor";
	private static final String A_SKIN_SELECTION_COLOR = "zimbraSkinSelectionColor";

	private static final String A_SKIN_LOGO_LOGIN_BANNER = "zimbraSkinLogoLoginBanner";
	private static final String A_SKIN_LOGO_APP_BANNER = "zimbraSkinLogoAppBanner";
	private static final String A_SKIN_LOGO_URL = "zimbraSkinLogoURL";

	private static final String A_HELP_ADMIN_URL = "zimbraHelpAdminURL";
	private static final String A_HELP_ADVANCED_URL = "zimbraHelpAdvancedURL";
	private static final String A_HELP_DELEGATED_URL = "zimbraHelpDelegatedURL";
	private static final String A_HELP_STANDARD_URL = "zimbraHelpStandardURL";

	private static final String H_USER_AGENT = "User-Agent";

    private static final String C_SKIN = "ZM_SKIN";
    private static final String C_ADMIN_SKIN = "ZA_SKIN";

    private static final String T_CSS = "css";
    private static final String T_HTML = "html";
    private static final String T_JAVASCRIPT = "javascript";

    private static final String N_SKIN = "skin";
    private static final String N_IMAGES = "images";

    private static final String SKIN_MANIFEST = "manifest.xml";

    private static final String CLIENT_STANDARD = "standard";
    private static final String CLIENT_ADVANCED = "advanced";

    private static final Pattern RE_IFDEF = Pattern.compile("^\\s*#ifdef\\s+(.*?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_IFNDEF = Pattern.compile("^\\s*#ifndef\\s+(.*?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_ENDIF = Pattern.compile("^\\s*#endif(\\s+.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_ELSE = Pattern.compile("^\\s*#else\\s*$", Pattern.CASE_INSENSITIVE);

    private static final String RE_COMMENTS = "/\\*[^*]*\\*+([^/][^*]*\\*+)*/";
    private static final String RE_WHITESPACE = "\\s+";

	private static final Pattern RE_VERSION = Pattern.compile("\\d+\\.\\d+");

    private static final String IMAGE_CSS = "img/images.css";

    private static final Map<String, String> TYPES = new HashMap<String, String>();

    static {
        TYPES.put("css", "text/css");
        TYPES.put("html", "text/html");
        TYPES.put("js", "text/javascript");
        TYPES.put("plain", "text/plain");
    }

    //
    // Data
    //

    /**
     * <ul>
     * <li>Key: client:skin/templates={true|false|split|only}:browser[:locale]
     * (e.g. standard:beach/templates=split:GECKO NAVIGATOR MACINTOSH:en_US)
     * <li>Value: Map
     * <ul>
     * <li>Key: request uri
     * <li>Value: String buffer
     * </ul>
     * </ul>
     */
    private Map<String, Map<String, String>> cache =
            new HashMap<String, Map<String, String>>();

	/**
	 * <strong>Note:</strong>
	 * This is needed because only the generate method knows if the
	 * templates were included. But we need that information on
	 * subsequent requests so that we can tell the callee if the
	 * templates were included.
	 * <p>
	 * Not knowing on subsequent requests whether templates were
	 * included caused bug 26563 and a 0-byte skin.js file to be
	 * requested even though everything had been inlined into
	 * launchZCS.jsp. 
	 */
	private Map<String,Boolean> included = new HashMap<String,Boolean>();

    //
    // HttpServlet methods
    //

    public void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {
        doGet(req, resp);
    }

    public void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {
        String uri = getRequestURI(req);
        String contentType = getContentType(uri);
        String type = contentType.replaceAll("^.*/", "");
		String debugStr = req.getParameter(P_DEBUG);
		boolean debug =  debugStr != null && (debugStr.equals(Boolean.TRUE.toString()) || debugStr.equals("1")); 
        String client = req.getParameter(P_CLIENT);
        if (client == null) {
            client = CLIENT_ADVANCED;
        }

        String userAgent = getUserAgent(req);
        Map<String, String> macros = parseUserAgent(userAgent);
        String browserType = getMacroNames(macros.keySet());

		String skin = getSkin(req);
		String templates = req.getParameter(P_TEMPLATES);
		if (templates == null) templates = V_TRUE;

        Locale locale = getLocale(req);
        String cacheId = client + ":" + skin + "/templates=" + templates + ":" + browserType;
        if (type.equals(T_JAVASCRIPT) || type.equals(T_CSS)) {
            cacheId += ":" + locale;
        }

        if (ZimbraLog.webclient.isDebugEnabled()) {
			ZimbraLog.webclient.debug("DEBUG: === debug is " + debug+" ("+debugStr+") ===");
			ZimbraLog.webclient.debug("DEBUG: uri=" + uri);
			ZimbraLog.webclient.debug("DEBUG: type=" + type);
			ZimbraLog.webclient.debug("DEBUG: contentType=" + contentType);
            ZimbraLog.webclient.debug("DEBUG: client=" + client);
            ZimbraLog.webclient.debug("DEBUG: skin=" + skin);
			ZimbraLog.webclient.debug("DEBUG: templates="+templates);
			ZimbraLog.webclient.debug("DEBUG: browserType=" + browserType);
            ZimbraLog.webclient.debug("DEBUG: locale=" + locale);
            ZimbraLog.webclient.debug("DEBUG: cacheId=" + cacheId);
        }

        // generate buffer
        Map<String, String> buffers = cache.get(cacheId);
        String buffer = buffers != null && !debug ? buffers.get(uri) : null;
        if (buffer == null) {
            if (ZimbraLog.webclient.isDebugEnabled()) ZimbraLog.webclient.debug("DEBUG: generating buffer");
            buffer = generate(req, resp, cacheId, macros, type, client, locale, templates);
            if (!debug) {
                if (type.equals(T_CSS)) {
                    CssCompressor compressor = new CssCompressor(new StringReader(buffer));
                    StringWriter out = new StringWriter();
                    compressor.compress(out, 0);
                    buffer = out.toString();
                }
                if (type.equals(T_JAVASCRIPT)) {
                    JavaScriptCompressor compressor = new JavaScriptCompressor(new StringReader(buffer), new ErrorReporter() {

                        public void warning(String message, String sourceName,
                                            int line, String lineSource, int lineOffset) {
                            if (line < 0) {
                                ZimbraLog.webclient.warn("\n" + message);
                            } else {
                                ZimbraLog.webclient.warn("\n" + line + ':' + lineOffset + ':' + message);
                            }
                        }

                        public void error(String message, String sourceName,
                                          int line, String lineSource, int lineOffset) {
                            if (line < 0) {
                                ZimbraLog.webclient.error("\n" + message);
                            } else {
                                ZimbraLog.webclient.error("\n" + line + ':' + lineOffset + ':' + message);
                            }
                        }

                        public EvaluatorException runtimeError(String message, String sourceName,
                                                               int line, String lineSource, int lineOffset) {
                            error(message, sourceName, line, lineSource, lineOffset);
                            return new EvaluatorException(message);
                        }
                    });
                    StringWriter out = new StringWriter();
                    compressor.compress(out, 0, true, false, false, false);
                    buffer = out.toString();
                }
                if (buffers == null) {
                    buffers = new HashMap<String, String>();
                    cache.put(cacheId, buffers);
                }
                buffers.put(uri, buffer);
            }
        } else {
            if (ZimbraLog.webclient.isDebugEnabled()) ZimbraLog.webclient.debug("DEBUG: using previous buffer");
        }

        // write buffer
        try {
            // We browser sniff so need to make sure any caches do the same.
            resp.addHeader("Vary", "User-Agent");
            // Cache It!
            resp.setHeader("Cache-control", "public, max-age=604800");
            resp.setContentType(contentType);
            resp.setContentLength(buffer.length());
        }
        catch (IllegalStateException e) {
            // ignore -- thrown if called from including JSP
        }

		String compressStr = req.getParameter(P_COMPRESS);
		boolean compress = compressStr != null && (compressStr.equals("true") || compressStr.equals("1"));
		compress = compress && macros.get("MSIE_6") == null;
		if (compress) {
			try {
				resp.setHeader("Content-Encoding", "gzip");
			}
			catch (IllegalStateException e) {
				compress = false;
			}
		}
		try {
            OutputStream out = resp.getOutputStream();
            byte[] bytes = buffer.getBytes("UTF-8");
			if (compress) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream(bytes.length);
				OutputStream gzos = new GZIPOutputStream(bos);
				gzos.write(bytes);
				gzos.close();
				bytes = bos.toByteArray();
			}
			out.write(bytes);
			out.flush();
		}
        catch (IllegalStateException e) {
            // use writer if called from including JSP
            PrintWriter out = resp.getWriter();
            out.print(buffer);
			out.flush();
		}

		Boolean included = this.included.get(cacheId);
		if (included != null) {
			req.setAttribute(A_TEMPLATES_INCLUDED, included);
		}
    } // doGet(HttpServletRequest,HttpServletResponse)

    //
    // Private methods
    //

    private Locale getLocale(HttpServletRequest req) {
		String language = null, country = null, variant = null;
		String locale = req.getParameter(P_LOCALE);
		if (locale != null) {
			StringTokenizer tokenizer = new StringTokenizer(locale, "_");
			language = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
			country = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
			variant = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
		}
		else {
			language = req.getParameter(P_LANGUAGE);
			country = req.getParameter(P_COUNTRY);
			variant = req.getParameter(P_VARIANT);
		}
		if (language != null) {
            if (country != null) {
                if (variant != null) {
                    return new Locale(language, country, variant);
                }
                return new Locale(language, country);
            }
            return new Locale(language);
        }
        return req.getLocale();
    } // getLocale(HttpServletRequest):Locale

    private String generate(HttpServletRequest req, HttpServletResponse resp,
                            String cacheId, Map<String, String> macros,
                            String type, String client, Locale requestedLocale,
							String templatesParam)
            throws IOException {
        String commentStart = "/* ";
        String commentContinue = " * ";
        String commentEnd = " */";
        if (type.equals(T_HTML)) {
            commentStart = "<!-- ";
            commentContinue = " - ";
            commentEnd = " -->";
        }

        // create data buffers
        CharArrayWriter cout = new CharArrayWriter(4096 << 2); // 16K buffer to start
        PrintWriter out = new PrintWriter(cout);

        // get data
        String skin = getSkin(req);
        out.println(commentStart);
        for (String mname : macros.keySet()) {
            String mvalue = macros.get(mname);
            out.print(commentContinue);
            out.println("#define " + mname + " " + mvalue);
        }
        out.println(commentEnd);
        out.println();

        String uri = getRequestURI(req);
        String filenames = uri;
        String ext = "." + type;

        int slash = uri.lastIndexOf('/');
        if (slash != -1) {
            filenames = uri.substring(slash + 1);
        }

        int dot = filenames.lastIndexOf('.');
        if (dot != -1) {
            ext = filenames.substring(dot);
            filenames = filenames.substring(0, dot);
        }

        ServletContext context = getServletContext();

        String rootDirname = context.getRealPath("/");
        File rootDir = new File(rootDirname);
        File fileDir = new File(rootDir, type);
        String skinDirname = context.getRealPath("/skins/" + skin);
        File skinDir = new File(skinDirname);
        File manifestFile = new File(skinDir, SKIN_MANIFEST);

		// domain overrides
		Map<String,String> substOverrides = null;
		try {
			Provisioning provisioning = Provisioning.getInstance();
			String serverName = req.getServerName();
			Domain domain = provisioning.get(DomainBy.virtualHostname, serverName);
			if (domain != null) {
				substOverrides = new HashMap<String,String>();
				// colors
				substOverrides.put(Manifest.S_SKIN_FOREGROUND_COLOR, domain.getAttr(A_SKIN_FOREGROUND_COLOR));
				substOverrides.put(Manifest.S_SKIN_BACKGROUND_COLOR, domain.getAttr(A_SKIN_BACKGROUND_COLOR));
				substOverrides.put(Manifest.S_SKIN_SECONDARY_COLOR, domain.getAttr(A_SKIN_SECONDARY_COLOR));
				substOverrides.put(Manifest.S_SKIN_SELECTION_COLOR, domain.getAttr(A_SKIN_SELECTION_COLOR));
				// images
				substOverrides.put(Manifest.S_SKIN_LOGO_LOGIN_BANNER, domain.getAttr(A_SKIN_LOGO_LOGIN_BANNER));
				substOverrides.put(Manifest.S_SKIN_LOGO_APP_BANNER, domain.getAttr(A_SKIN_LOGO_APP_BANNER));
				substOverrides.put(Manifest.S_SKIN_LOGO_URL, domain.getAttr(A_SKIN_LOGO_URL));
				// help
				substOverrides.put(Manifest.S_HELP_ADMIN_URL, domain.getAttr(A_HELP_ADMIN_URL));
				substOverrides.put(Manifest.S_HELP_ADVANCED_URL, domain.getAttr(A_HELP_ADVANCED_URL));
				substOverrides.put(Manifest.S_HELP_DELEGATED_URL, domain.getAttr(A_HELP_DELEGATED_URL));
				substOverrides.put(Manifest.S_HELP_STANDARD_URL, domain.getAttr(A_HELP_STANDARD_URL));
			}
		}
		catch (Exception e) {
			if (ZimbraLog.webclient.isDebugEnabled()) {
				ZimbraLog.webclient.debug("!!! Unable to get domain config");
			}
		}

        // load manifest
        Manifest manifest = new Manifest(manifestFile, macros, client, substOverrides);

        // process input files
        StringTokenizer tokenizer = new StringTokenizer(filenames, ",");
        while (tokenizer.hasMoreTokens()) {
            String filename = tokenizer.nextToken();
            if (ZimbraLog.webclient.isDebugEnabled()) ZimbraLog.webclient.debug("DEBUG: filename " + filename);
            String filenameExt = filename + ext;

            List<File> files = new LinkedList<File>();

            if (filename.equals(N_SKIN)) {
                if (type.equals(T_CSS)) {
					for (File file : manifest.getFiles(type)) {
						files.add(file);
						addLocaleFiles(files, requestedLocale, file.getParentFile(), file.getName(), "");
					}

					File file = new File(skinDir, IMAGE_CSS);
					files.add(file);
					addLocaleFiles(files, requestedLocale, file.getParentFile(), file.getName(), "");
				}
				else if (type.equals(T_JAVASCRIPT)) {
					// decide whether to include templates
					boolean only = templatesParam.equals(V_ONLY);
					boolean split = templatesParam.equals(V_SPLIT);
					boolean include = only || split || templatesParam.equals(V_TRUE);

					// ignore main skin files if only want templates
					if (!only) {
						for (File file : manifest.getFiles(type)) {
							files.add(file);
							// TODO: Not sure if we want to allow different skin JS files
							//       (aside from templates) based on locale.
//							addLocaleFiles(files, requestedLocale, file.getParentFile(), file.getName(), "");
						}
					}

					// include templates, unless request to split and too big
					if (include) {
						List<File> templates = manifest.templateFiles();
						boolean included = includeTemplates(templates, split);
						if (included) {
							for (File file : templates) {
								// TODO: optimize
								files.add(new File(file.getParentFile(), file.getName() + ".js"));
								String templateFilename = file.getName().replaceAll("\\.template$", ""); 
								addLocaleFiles(files, requestedLocale, file.getParentFile(), templateFilename, ".template.js");
							}
						}
						this.included.put(cacheId, included);
					}
				}
				else {
					files.addAll(manifest.getFiles(type));
					// TODO: Add locale variants? Probably not...
				}
			} else {
                File file = new File(fileDir, filenameExt);
                if (ZimbraLog.webclient.isDebugEnabled())
                    ZimbraLog.webclient.debug("DEBUG: file " + file.getAbsolutePath());
                if (!file.exists() && type.equals(T_CSS) && filename.equals(N_IMAGES)) {
                    file = new File(rootDir, IMAGE_CSS);
                    if (ZimbraLog.webclient.isDebugEnabled())
                        ZimbraLog.webclient.debug("DEBUG: !file.exists() " + file.getAbsolutePath());
                }
                files.add(file);
				if (type.equals(T_CSS) || type.equals(T_JAVASCRIPT)) {
					addLocaleFiles(files, requestedLocale, fileDir, filename, ext);
				}
			}

            for (File file : files) {
                if (!file.exists()) {
                    out.print(commentStart);
                    out.print("Error: file doesn't exist - " + file.getAbsolutePath().replaceAll("^.*/webapps/",""));
                    out.println(commentEnd);
                    out.println();
                    continue;
                }
                if (ZimbraLog.webclient.isDebugEnabled())
                    ZimbraLog.webclient.debug("DEBUG: preprocess " + file.getAbsolutePath());
                preprocess(file, cout, macros, manifest,
                        commentStart, commentContinue, commentEnd);
            }
        }

        // return data
        out.flush();
        return cout.toString();
    }

	static void addLocaleFiles(List<File> files, Locale requestedLocale,
							   File dir, String filename, String ext) {
		Locale defaultLocale = Locale.getDefault();
		Locale[] locales = defaultLocale.equals(requestedLocale)
						 ? new Locale[]{ requestedLocale }
						 : new Locale[]{ defaultLocale, requestedLocale };
		for (Locale locale : locales) {
			// NOTE: Overrides are loaded in backwards order from
			//       resource bundles because CSS/JS that appears
			//       later in the file take precedence. This is
			//       different than resource bundles where the
			//       first entry seen takes precedence.
			String language = locale.getLanguage();
			File langFile = new File(dir, filename+"_"+language+ext);
			if (langFile.exists()) {
				files.add(langFile);
			}
			String country = locale.getCountry();
			if (country != null && country.length() > 0) {
				File langCountryFile = new File(dir, filename+"_"+language+"_"+country+ext);
				if (langCountryFile.exists()) {
					files.add(langCountryFile);
				}
				String variant = locale.getVariant();
				if (variant != null && variant.length() > 0) {
					File langCountryVariantFile = new File(dir, filename+"_"+language+"_"+country+"_"+variant+ext);
					if (langCountryVariantFile.exists()) {
						files.add(langCountryVariantFile);
					}
				}
			}
		}
	}

	static boolean includeTemplates(List<File> templates, boolean split) {
		boolean include = true;
		if (split) {
			long size = 0;
			for (File file : templates) {
				// TODO: optimize
				File template = new File(file.getParentFile(), file.getName()+".js");
				size += template.exists() ? template.length() : 0;
			}
			include = size <= MAX_INCLUDED_TEMPLATES_SIZE;
		}
		return include;
	}

	static void preprocess(File file,
                           Writer writer,
                           Map<String, String> macros,
                           Manifest manifest,
                           String commentStart,
                           String commentContinue,
                           String commentEnd)
            throws IOException {
        PrintWriter out = new PrintWriter(writer);

        out.println(commentStart);
        out.print(commentContinue);
        out.println("File: " + file.getAbsolutePath().replaceAll("^.*/webapps/",""));
        out.println(commentEnd);
        out.println();

        BufferedReader in = new BufferedReader(new FileReader(file));
        Stack<Boolean> ignore = new Stack<Boolean>();
        ignore.push(false);
        String line;
        while ((line = in.readLine()) != null) {
            Matcher ifdef = RE_IFDEF.matcher(line);
            if (ifdef.matches()) {
//				out.print(commentStart);
//				out.print("Info: "+line);
//				out.println(commentEnd);
                String macroName = ifdef.group(1);
                ignore.push(ignore.peek() || macros.get(macroName) == null);
                continue;
            }
            Matcher ifndef = RE_IFNDEF.matcher(line);
            if (ifndef.matches()) {
//				out.print(commentStart);
//				out.print("Info: "+line);
//				out.println(commentEnd);
                String macroName = ifndef.group(1);
                ignore.push(ignore.peek() || macros.get(macroName) != null);
                continue;
            }
            Matcher endif = RE_ENDIF.matcher(line);
            if (endif.matches()) {
//				out.print(commentStart);
//				out.print("Info: "+line);
//				out.println(commentEnd);
                ignore.pop();
                continue;
            }
            Matcher elseMatcher = RE_ELSE.matcher(line);
            if (elseMatcher.matches()) {
//                out.print(commentStart);
//                out.print("Info: "+line);
//                out.println(commentEnd);
                boolean ignoring = ignore.pop();
                ignore.push(!ignoring);
                continue;
            }
            if (ignore.peek()) {
                continue;
            }

            if (manifest != null) {
                line = manifest.replace(line);
            }
            out.println(line);
        }
        in.close();
    }

    //
    // Private static functions
    //

    /**
     * Return the request URI without any path parameters.
     * We do this because we are only concerned with the type and
     * filenames that we need to aggregate and return. And various
     * web containers may insert the jsessionid path parameter to
     * URIs returned by <code>getRequestURI</code> if no session
     * ID cookie has been set.
     *
     * @param req The HTTP request
     * @return Request URI
     */
    private static String getRequestURI(HttpServletRequest req) {
		String servletPath = req.getParameter(P_SERVLET_PATH);
		if (servletPath == null) servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();
        return pathInfo != null ? servletPath + pathInfo : servletPath;
    }

    private static Cookie getCookie(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(name)) {
                    return cookie;
                }
            }
        }
        return null;
    }

    private static String getMacroNames(Set<String> mnames) {
        Set<String> snames = new TreeSet<String>(mnames);
        StringBuilder str = new StringBuilder();
        for (String mname : snames) {
            str.append(mname);
            str.append(' ');
        }
        return str.toString().trim();
    }

    private String getSkin(HttpServletRequest req) {
        String zimbraAdminURL = "/zimbraAdmin";
        try {
            Context initCtx = new InitialContext();
            Context envCtx = (Context) initCtx.lookup("java:comp/env");
            zimbraAdminURL = (String) envCtx.lookup("adminUrl");
        } catch (NamingException ne) {
            ne.printStackTrace();
        }
        if (zimbraAdminURL == null) {
            zimbraAdminURL = "/zimbraAdmin";
        }
        String skin = req.getParameter(P_SKIN);
        if (skin == null) {
            String contentPath = req.getContextPath();
            Cookie cookie;
            if (contentPath != null && contentPath.equalsIgnoreCase(zimbraAdminURL)) {
                cookie = getCookie(req, C_ADMIN_SKIN);
            } else {
                cookie = getCookie(req, C_SKIN);
            }
            skin = cookie != null ? cookie.getValue() : getServletContext().getInitParameter(P_DEFAULT_SKIN);
        }
        File manifest = new File(getServletContext().getRealPath("/skins/"+skin+"/"+SKIN_MANIFEST));
        if (!manifest.exists()) {
            skin = getServletContext().getInitParameter(P_DEFAULT_SKIN);
        }
        return StringUtil.escapeHtml(skin);
    }

    private static String getContentType(String uri) {
        int index = uri.lastIndexOf('/');
        if (index != -1) {
            uri = uri.substring(0, index);
        }
        index = uri.lastIndexOf('/');
		String key = index != -1 ? uri.substring(index + 1) : "plain";
		String type = TYPES.get(key);
		return type != null ? type : TYPES.get("plain");
    }

    private static String getUserAgent(HttpServletRequest req) {
        String agent = req.getParameter(P_USER_AGENT);
        if (agent == null) {
            agent = req.getHeader(H_USER_AGENT);
        }
        return agent;
    }

    private static Map<String, String> parseUserAgent(String agent) {
        Map<String, String> macros = new HashMap<String, String>();

        // state
        double browserVersion = -1.0;
        double geckoDate = 0;
        double mozVersion = -1;
        boolean isMac = false;
        boolean isWindows = false;
        boolean isLinux = false;
        boolean isNav = false;
        boolean isIE = false;
        boolean isNav4 = false;
        boolean trueNs = false;
        boolean isNav6 = false;
        boolean isNav6up = false;
        boolean isNav7 = false;
        boolean isIE3 = false;
        boolean isIE4 = false;
        boolean isIE4up = false;
        boolean isIE5 = false;
        boolean isIE5_5 = false;
        boolean isIE5up = false;
        boolean isIE5_5up = false;
        boolean isIE6 = false;
        boolean isIE6up = false;
        boolean isIE7 = false;
        boolean isIE7up = false;
        boolean isFirefox = false;
        boolean isFirefox1up = false;
        boolean isFirefox1_5up = false;
        boolean isMozilla = false;
        boolean isMozilla1_4up = false;
        boolean isSafari = false;
        boolean isGeckoBased = false;
		boolean isGecko1_8up = false;
		boolean isOpera = false;
        boolean isIPhone = false;

        // parse user agent
		if (agent == null) agent = "";
        String agt = agent.toLowerCase();
        StringTokenizer agtArr = new StringTokenizer(agt, " ;()");
        int index = -1;
        boolean isSpoofer = false;
        boolean isWebTv = false;
        boolean isHotJava = false;
        boolean beginsWithMozilla = false;
        boolean isCompatible = false;

		if (agtArr.hasMoreTokens()) {
            String token = agtArr.nextToken();
            Pattern pattern = Pattern.compile("\\s*mozilla");
            Matcher mozilla = pattern.matcher(token);
            if (mozilla.find()) {
                index = mozilla.start();
                beginsWithMozilla = true;
                browserVersion = parseFloat(token.substring(index + 8));
                isNav = true;
            }
            do {
                if (token.indexOf("compatible") != -1) {
                    isCompatible = true;
                    isNav = false;
                } else if ((token.indexOf("opera")) != -1) {
                    isOpera = true;
                    isNav = false;
                    if (agtArr.hasMoreTokens()) {
                        browserVersion = parseVersion(agtArr.nextToken());
                    }
                } else if ((token.indexOf("spoofer")) != -1) {
                    isSpoofer = true;
                    isNav = false;
                } else if ((token.indexOf("webtv")) != -1) {
                    isWebTv = true;
                    isNav = false;
                } else if ((token.indexOf("iphone")) != -1) {
                    isIPhone = true;
                } else if ((token.indexOf("hotjava")) != -1) {
                    isHotJava = true;
                    isNav = false;
                } else if (token.indexOf("msie") != -1) {
                    isIE = true;
                    if (agtArr.hasMoreTokens()) {
                        browserVersion = parseVersion(agtArr.nextToken());
                    }
                } else if ((index = token.indexOf("gecko/")) != -1) {
                    isGeckoBased = true;
                    geckoDate = Float.parseFloat(token.substring(index + 6));
                } else if ((index = token.indexOf("rv:")) != -1) {
                    mozVersion = parseVersion(token.substring(index + 3));
                    browserVersion = mozVersion;
                } else if ((index = token.indexOf("firefox/")) != -1) {
                    isFirefox = true;
                    browserVersion = parseVersion(token.substring(index + 8));
                } else if ((index = token.indexOf("netscape6/")) != -1) {
                    trueNs = true;
                    browserVersion = parseVersion(token.substring(index + 10));
                } else if ((index = token.indexOf("netscape/")) != -1) {
                    trueNs = true;
                    browserVersion = parseVersion(token.substring(index + 9));
                } else if ((index = token.indexOf("safari/")) != -1) {
                    isSafari = true;
                    browserVersion = parseVersion(token.substring(index + 7));
                } else if (token.indexOf("windows") != -1) {
                    isWindows = true;
                } else if ((token.indexOf("macintosh") != -1) || (token.indexOf("mac_") != -1)) {
                    isMac = true;
                } else if (token.indexOf("linux") != -1) {
                    isLinux = true;
                }

                token = agtArr.hasMoreTokens() ? agtArr.nextToken() : null;
            } while (token != null);

            isIE = (isIE && !isOpera);
			isIE3 = (isIE && (browserVersion < 4));
			isIE4 = (isIE && (browserVersion == 4.0));
			isIE4up = (isIE && (browserVersion >= 4));
			isIE5 = (isIE && (browserVersion == 5.0));
			isIE5_5 = (isIE && (browserVersion == 5.5));
			isIE5up = (isIE && (browserVersion >= 5.0));
			isIE5_5up = (isIE && (browserVersion >= 5.5));
			isIE6 = (isIE && (browserVersion == 6.0));
			isIE6up = (isIE && (browserVersion >= 6.0));
			isIE7 = (isIE && (browserVersion == 7.0));
			isIE7up = (isIE && (browserVersion >= 7.0));

			// Note: Opera and WebTV spoof Navigator. We do strict client detection.
			isNav = (beginsWithMozilla && !isSpoofer && !isCompatible && !isOpera && !isWebTv && !isHotJava && !isSafari);
            isNav4 = (isNav && (browserVersion == 4) && (!isIE));
            isNav6 = (isNav && trueNs && (browserVersion >= 6.0) && (browserVersion < 7.0));
            isNav6up = (isNav && trueNs && (browserVersion >= 6.0));
            isNav7 = (isNav && trueNs && (browserVersion == 7.0));

            isMozilla = ((isNav && mozVersion > -1.0 && isGeckoBased && (geckoDate != 0)));
            isMozilla1_4up = (isMozilla && (mozVersion >= 1.4));
            isFirefox = ((isMozilla && isFirefox));
            isFirefox1up = (isFirefox && browserVersion >= 1.0);
            isFirefox1_5up = (isFirefox && browserVersion >= 1.5);
			isGecko1_8up = (isGeckoBased && browserVersion >= 1.8);

            // operating systems
			define(macros, "LINUX", isLinux);
			define(macros, "MACINTOSH", isMac);
			define(macros, "WINDOWS", isWindows);

            // browser variants
			define(macros, "FIREFOX", isFirefox);
			define(macros, "FIREFOX_1_OR_HIGHER", isFirefox1up);
			define(macros, "FIREFOX_1_5_OR_HIGHER", isFirefox1_5up);
			define(macros, "GECKO", isGeckoBased);
			define(macros, "GECKO_1_8_OR_HIGHER", isGecko1_8up);
			define(macros, "HOTJAVA", isHotJava);
			define(macros, "IPHONE", isIPhone);
			define(macros, "MOZILLA", isMozilla);
			define(macros, "MOZILLA_1_4_OR_HIGHER", isMozilla1_4up);
			define(macros, "MSIE", isIE);
			define(macros, "MSIE_LOWER_THAN_7", isIE && !isIE7up);
			define(macros, "MSIE_3", isIE3);
			define(macros, "MSIE_4", isIE4);
			define(macros, "MSIE_4_OR_HIGHER", isIE4up);
			define(macros, "MSIE_5", isIE5);
			define(macros, "MSIE_5_OR_HIGHER", isIE5up);
			define(macros, "MSIE_5_5", isIE5_5);
			define(macros, "MSIE_5_5_OR_HIGHER", isIE5_5up);
			define(macros, "MSIE_6", isIE6);
			define(macros, "MSIE_6_OR_HIGHER", isIE6up);
			define(macros, "MSIE_7", isIE7);
			define(macros, "MSIE_7_OR_HIGHER", isIE7up);
            define(macros, "NAVIGATOR", isNav);
            define(macros, "NAVIGATOR_4", isNav4);
            define(macros, "NAVIGATOR_6", isNav6);
            define(macros, "NAVIGATOR_6_OR_HIGHER", isNav6up);
            define(macros, "NAVIGATOR_7", isNav7);
            define(macros, "NAVIGATOR_COMPATIBLE", isCompatible);
            define(macros, "OPERA", isOpera);
            define(macros, "SAFARI", isSafari);
            define(macros, "SAFARI_2", isSafari && browserVersion == 2.0);
            define(macros, "SAFARI_2_OR_HIGHER", isSafari && browserVersion >= 2.0);
            define(macros, "SAFARI_3", isSafari && browserVersion >= 3.0);
            define(macros, "WEBTV", isWebTv);
        }

        return macros;

    }

    private static double parseFloat(String s) {
        try {
            return Float.parseFloat(s);
        }
        catch (NumberFormatException e) {
            // ignore
        }
        return -1.0;
    }

	private static double parseVersion(String s) {
		Matcher matcher = RE_VERSION.matcher(s);
		if (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            return parseFloat(s.substring(start, end));
		}
		return parseFloat(s);
	}

    private static void define(Map<String, String> macros, String mname, boolean defined) {
        if (defined) {
            macros.put(mname, Boolean.TRUE.toString());
        }
    }

    //
    // Classes
    //

    static class Manifest {

        //
        // Constants
        //

		public static final String S_SKIN_FOREGROUND_COLOR = "TxtC";
		public static final String S_SKIN_BACKGROUND_COLOR = "AppC";
		public static final String S_SKIN_SECONDARY_COLOR = "AltC";
		public static final String S_SKIN_SELECTION_COLOR = "SelC";

		public static final String S_SKIN_LOGO_LOGIN_BANNER = "LoginBannerImg";
		public static final String S_SKIN_LOGO_APP_BANNER = "AppBannerImg";
		public static final String S_SKIN_LOGO_URL = "LogoURL";

		private static final String S_HELP_ADMIN_URL = "HelpAdminURL";
		private static final String S_HELP_ADVANCED_URL = "HelpAdvancedURL";
		private static final String S_HELP_DELEGATED_URL = "HelpDelegatedURL";
		private static final String S_HELP_STANDARD_URL = "HelpStandardURL";

        private static final String E_SKIN = "skin";
        private static final String E_SUBSTITUTIONS = "substitutions";
        private static final String E_CSS = "css";
        private static final String E_HTML = "html";
        private static final String E_SCRIPT = "script";
        private static final String E_TEMPLATES = "templates";
        private static final String E_FILE = "file";
        private static final String E_COMMON = "common";
        private static final String E_STANDARD = "standard";
        private static final String E_ADVANCED = "advanced";

        private static final Pattern RE_TOKEN = Pattern.compile("@.+?@");
        private static final Pattern RE_SKIN_METHOD = Pattern.compile("@(\\w+)\\((.*?)\\)@");


        //
        // Data
        //

        private String client;

        private List<File> substList = new LinkedList<File>();
        private List<File> cssList = new LinkedList<File>();
        private List<File> htmlList = new LinkedList<File>();
        private List<File> scriptList = new LinkedList<File>();
        private List<File> templateList = new LinkedList<File>();
        private List<File> resourceList = new LinkedList<File>();
        private Map<String, String> macros;

        private Properties substitutions = new Properties();

        //
        // Constructors
        //

        public Manifest(File manifestFile, Map<String, String> macros, String client,
						Map<String,String> substOverrides)
                throws IOException {
            this.client = client;
            // rememeber the macros passed in (for skin substitution functions)
            this.macros = macros;
            
            // load document
            Document document;
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setValidating(false);
                DocumentBuilder builder = factory.newDocumentBuilder();
                document = builder.parse(manifestFile);
            }
            catch (IOException e) {
                throw e;
            }
            catch (Exception e) {
                throw new IOException(e.getMessage());
            }

            // gather files
            File skinDir = manifestFile.getParentFile();
            getFiles(document, E_SUBSTITUTIONS, skinDir, substList);
            getFiles(document, E_CSS, skinDir, cssList);
            getFiles(document, E_HTML, skinDir, htmlList);
            getFiles(document, E_SCRIPT, skinDir, scriptList);
            getFiles(document, E_TEMPLATES, skinDir, templateList);

            // process substitutions
            for (File file : substList) {
                if (ZimbraLog.webclient.isDebugEnabled()) ZimbraLog.webclient.debug("DEBUG: subst file = " + file);
                try {
                    CharArrayWriter out = new CharArrayWriter(4096); // 4K
                    SkinResources.preprocess(file, out, macros, null, "#", "#", "#");
                    String content = out.toString();
                    // NOTE: properties files should be ISO-Latin-1 with
                    //       escaped Unicode char sequences.
                    byte[] bytes = content.getBytes("ISO-8859-1");

                    InputStream in = new ByteArrayInputStream(bytes);

                    substitutions.load(in);
					if (substOverrides != null) {
						for (String key : substOverrides.keySet()) {
							String value = substOverrides.get(key);
							if (value == null) continue;
							substitutions.setProperty(key, value);
							if (key.equals(S_SKIN_BACKGROUND_COLOR) || key.equals(S_SKIN_FOREGROUND_COLOR) ||
								key.equals(S_SKIN_SELECTION_COLOR)) {
								Color color = getColor(null, value);
								for (int i = 5; i < 100; i += 5) {
									float delta = (float)i / (float)100;
									substitutions.setProperty(
										key + "-" + (i < 10 ? "0" : "") + i, lightenColor(color, delta)
									);
									substitutions.setProperty(
										key + "+" + (i < 10 ? "0" : "") + i, darkenColor(color, delta)
									);
								}
							}
						}
					}
				}
                catch (Throwable t) {
                    ZimbraLog.webclient.debug("ERROR loading subst file: " + file);
                }

                if (ZimbraLog.webclient.isDebugEnabled())
                    ZimbraLog.webclient.debug("DEBUG: _SkinName_ = " + substitutions.getProperty("_SkinName_"));
            }

            Stack<String> stack = new Stack<String>();
            Enumeration substKeys = substitutions.propertyNames();
            if (ZimbraLog.webclient.isDebugEnabled())
                ZimbraLog.webclient.debug("DEBUG: InsetBg (before) = " + substitutions.getProperty("InsetBg"));
            while (substKeys.hasMoreElements()) {
                stack.removeAllElements();

                String substKey = (String) substKeys.nextElement();
                if (substKey.equals("InsetBg")) {
                    if (ZimbraLog.webclient.isDebugEnabled())
                        ZimbraLog.webclient.debug("DEBUG: InsetBg (loop) = " + substitutions.getProperty("InsetBg"));
                }
                getProperty(stack, substKey);
            }
            if (ZimbraLog.webclient.isDebugEnabled())
                ZimbraLog.webclient.debug("DEBUG: InsetBg (after) = " + substitutions.getProperty("InsetBg"));

            if (ZimbraLog.webclient.isDebugEnabled())
                ZimbraLog.webclient.debug("DEBUG: _SkinName_ = " + substitutions.getProperty("_SkinName_"));
        } // <init>(File,Map<String,String>,String,String)

        //
        // Public methods
        //

        // lists

        public List<File> substitutionFiles() {
            return substList;
        }

        public List<File> cssFiles() {
            return cssList;
        }

        public List<File> htmlFiles() {
            return htmlList;
        }

        public List<File> scriptFiles() {
            return scriptList;
        }

        public List<File> templateFiles() {
            return templateList;
        }

        public List<File> resourceFiles() {
            return resourceList;
        }

        public List<File> getFiles(String type) {
            if (type.equals(SkinResources.T_CSS)) return cssFiles();
            if (type.equals(SkinResources.T_HTML)) return htmlFiles();
            if (type.equals(SkinResources.T_JAVASCRIPT)) return scriptFiles();
            return null;
        }

        // operations

        public String replace(String s) {
            return replace(null, s);
        }

        //
        // Private methods
        //

		private boolean isBrowser(String name) {
			String booleanStr = macros.get(name);
			return (booleanStr != null && booleanStr.equalsIgnoreCase("true"));		
		}
	
        public String getProperty(Stack<String> stack, String pname) {
            // check for cycles
            if (stack != null) {
                for (String s : stack) {
                    if (s.equals(pname)) {
                        return "/*ERR:" + pname + "*/";
                    }
                }
                stack.push(pname);
            }

            // substitute and return
            String pvalue = substitutions.getProperty(pname);
            pvalue = replace(stack, pvalue);
            if (stack != null) {
                stack.pop();
            }
            substitutions.setProperty(pname, pvalue);
            return pvalue;
        }

        private String replace(Stack<String> stack, String s) {
            if (s == null) {
                return "";
            }

			s = this.handleMethodCalls(stack, s);
            
            Matcher matcher = RE_TOKEN.matcher(s);
            if (!matcher.find()) {
                return s;
            }

            StringBuilder str = new StringBuilder();
            int offset = 0;
            do {
                int start = matcher.start();
                int end = matcher.end();

                String substKey = s.substring(start + 1, end - 1);
                String substValue = getProperty(stack, substKey);
                if (substValue != null) {
                    str.append(s.substring(offset, start));
                    str.append(substValue);
                } else {
                    str.append("/*");
                    str.append(s.substring(offset, end));
                    str.append("*/");
                }

                offset = end;
            } while (matcher.find(offset));
            str.append(s.substring(offset));

            return str.toString();
        }
        
                
        // handle a method call in a skin replacemented file
        //	syntax:    @methodName(param,param,param)@
        private String handleMethodCalls(Stack<String> stack, String s) {
        	Matcher matcher = RE_SKIN_METHOD.matcher(s);
        	if (!matcher.find()) return s;

            StringBuilder str = new StringBuilder();
            int offset = 0;
            do {
                int start = matcher.start();
				str.append(s.substring(offset, start));

				String operation = matcher.group(1).toLowerCase();
				String[] params = matcher.group(2).split(" *, *");
				
				try {
					String result;
					// "darken" or "-"
					if (operation.equals("darken") || operation.equals("+")) {
						result = outputDarkerColor(stack, params);
						
					// "lighten" or "-"
					} else if (operation.equals("lighten") || operation.equals("-")) {
						result = outputLighterColor(stack, params);
					
					// "border"
					} else if (operation.equals("border")) {
						result = outputBorder(stack, params);
					
					// "image" or "img"
					} else if (operation.equals("image") || operation.equals("img")) {
						result = outputImage(stack, params);
					
					// "cssShadow"
					} else if (operation.equals("cssshadow")) {
						result = outputCssShadow(stack, params);
					
					// "cssText" or "cssTextProp[ertie]s"
					} else if (operation.indexOf("csstext") == 0) {
						result = outputCssTextProperties(stack, params);
					
					// "cssValue"
					} else if (operation.indexOf("cssvalue") == 0) {
						result = outputCssValue(stack, params);

					// "css" or "cssProp[ertie]s"
					} else if (operation.indexOf("css") == 0) {
						result = outputCssProperties(stack, params);
					
					// "round" or "roundCorners"
					} else if (operation.indexOf("round") == 0) {
						result = outputRoundCorners(stack, params);
					
					// "opacity"
					} else if (operation.equals("opacity")) {
						result = outputOpacity(stack, params);
					
					} else {
						throw new IOException("Couldn't understand operation "+matcher.group(1)+".");
					}

					// and output the results in place
					str.append(result);
					
				} catch (IOException e) {
					str.append("/***"+e.getMessage()+"***/");
				}

                offset = matcher.end();
            } while (matcher.find(offset));
            str.append(s.substring(offset));
            return str.toString();
        }

        
        //
        //
        //	Color routines
        //
        //
        
		//
		// replace occurances of @Darken(color,percent)@ with the adjusted color
		//
        private String outputDarkerColor(Stack<String> stack, String[] params) throws IOException {
			Color color = this.getColor(stack, params[0]);
			float delta = (Float.parseFloat(params[1]) / 100);
			return this.darkenColor(color, delta);
        }

		//
		// replace occurances of @Lighten(color,percent)@ with the adjusted color
		//
        private String outputLighterColor(Stack<String> stack, String[] params) throws IOException {
			Color color = this.getColor(stack, params[0]);
			float delta = (Float.parseFloat(params[1]) / 100);
			return this.lightenColor(color, delta);
        }


		// darken a color object by given fraction, returns a hex color string
		private String darkenColor(Color color, float delta) {
			return colorToColorString(
						new Color(	darken(color.getRed(), delta),
									darken(color.getGreen(), delta),
									darken(color.getBlue(), delta)
						)
					);		
		}

		// lighten a color object by given fraction, returns a hex color string
		private String lightenColor(Color color, float delta) {
			return colorToColorString(
						new Color(	lighten(color.getRed(), delta),
									lighten(color.getGreen(), delta),
									lighten(color.getBlue(), delta)
						)
					);		
		}


		private int lighten(int value, float delta) {
			return (int) Math.max(0, Math.min(255, value + (255 - value) * delta));
		}

		private int darken(int value, float delta) {
			return (int) Math.max(0, Math.min(255, value * (1 - delta)));
		}

		// given a color (either '#fffff' or 'ffffff' or a substitution), 
		//	return a Color object that corresponds to that color.
		//
		// TODO: make this handle rgb(#,#,#) and 'ccc' or '#ccc'
		private Color getColor(Stack<String> stack, String colorStr) throws IOException {
			// if there is a space in there, strip everything after it
			//	(to remove '!important' and stuff like that
			Color color = Colors.getColor(colorStr.replaceAll(" .*$", ""));
			if (color == null) {
				String prop = getProperty(stack, colorStr);
				if (prop != null) {
					color = Colors.getColor(prop);
				}
			}
			if (color == null) {
				throw new IOException("Unknown color:" + colorStr);
			}
			return color;
		}

		private String colorToColorString(Color color) {
			if (color == null) return "NULL_COLOR";
			int[] rgb = { color.getRed(), color.getGreen(), color.getBlue() };
			StringBuilder str = new StringBuilder("#");
			for (int val : rgb) {
				if (val < 10) str.append("0");
				str.append(Integer.toHexString(val));
			}
			return str.toString();
		}


		
		//
		//
		//	CSS manipulation routines
		//
		//

		//
		// replace occurances of @border(size,type,color,colorDelta)@ with the CSS for the border
		//
		//	TODO: 	if more than 1 px, do pretty borders on Moz?
		//
        private String outputBorder(Stack<String> stack, String[] params) throws IOException {
			String size = (params.length > 0 ? params[0] : "1px");
			String type = (params.length > 1 ? params[1].toLowerCase() : "solid");
			Color color = (params.length > 2 ? this.getColor(stack, params[2]) : Color.decode("#fffff"));
			float delta = (float) (params.length > 3 ? (Float.parseFloat(params[3]) / 100) : .25);

			String sizeStr = (size.indexOf(" ") == -1 ? " " + size + ";" : "; border-width:" + size + ";");
			
			if (type.equals("transparent")) {
				if (isBrowser("MSIE_LOWER_THAN_7")) {
					return "margin:" + size +";border:0px;";
				} else {
					return "border:solid transparent" + sizeStr;
				}
			} else if (type.equals("solid")) {
				return "border:solid " + colorToColorString(color) + sizeStr;
				
			} else if (type.equals("inset") || type.equals("outset")) {
				String tlColor = (type.equals("inset") ? darkenColor(color, delta) : lightenColor(color, delta));
				String brColor = (type.equals("inset") ? lightenColor(color, delta) : darkenColor(color, delta));
				return "border:solid" + sizeStr + "border-color:"
							+ tlColor + " " + brColor + " " + brColor + " " + tlColor + ";";
			}
			throw new IOException("border("+type+"): type not understood: use 'transparent', 'solid', 'inset' or 'outset'");
		}
					
		//
		// replace occurances of @image(dir, filename.extension, width, height, repeat)@ with the CSS for the image 
		//		as a background-image (or filter for PNG's in IE)
		//
        private String outputImage(Stack<String> stack, String[] params) throws IOException {
			String dir = (params.length > 0 ? params[0] : "");
			String name = (params.length > 1 ? params[1] : null);
			String width = (params.length > 2 ? params[2] : null);
			String height = (params.length > 3 ? params[3] : null);
			String repeat = (params.length > 4 ? params[4] : null);

			if (name == null) throw new IOException("image(): specify directory, name, width, height");
			
			// if there is no extension in the name, assume it's a sub
			if (name.indexOf(".") == -1) {
				name = getProperty(stack, name);
			}
			if (name == null) throw new IOException("image(): specify directory, name, width, height");
			
			boolean isPNG = (name.toLowerCase().indexOf(".png") > -1);
			
			dir = (dir == null || dir.equals("") ? "" : getProperty(stack, dir));
			// make sure there's a slash between the directory and the image name
			if (!dir.equals("") && (dir.lastIndexOf("/") != dir.length()-1 || name.indexOf("/") != 0)) {
				dir = dir + "/";
			}

			String url = dir + name;
			
			if (isPNG && isBrowser("MSIE_LOWER_THAN_7")) {
				return "background-image:none;filter:progid:DXImageTransform.Microsoft.AlphaImageLoader(src='" + url + "'"
							+ (repeat != null && repeat.toLowerCase().indexOf("no") > -1 
									? "sizingMethod='crop');" : "sizingMethod='scale');")
							+ (width != null ? "width:"+width+";" : "")
							+ (height != null ? "height:"+height+";" : "");
			} else {
				return "background-image:url(" + url + ");"
							+ (repeat != null ? "background-repeat:"+repeat+";" : "")
							+ (width != null ? "width:"+width+";" : "")
							+ (height != null ? "height:"+height+";" : "");
			}
		}
		
		//
		// replace occurances of @cssValue(token, property)@ with the css value of that replacement token
		//
        private String outputCssValue(Stack<String> stack, String[] params) throws IOException {
			if (params.length != 2) throw new IOException("cssValue(): pass replacement, property");
			String token = params[0];
			String cssString = getProperty(stack, token);
			if (cssString == "") throw new IOException("cssValue(): '"+token+"' not found");

			Map<String, String> map = parseCSSProperties(cssString);
			return map.get(params[1]);
		}

		//
		// replace occurances of @cssProperties(token, property[..., property])@ with the css name:value pairs of the replacement token
		//
        private String outputCssProperties(Stack<String> stack, String[] params) throws IOException {
			if (params.length < 2) throw new IOException("cssProperties(): pass at least replacement, property");
			String token = params[0];
			String cssString = getProperty(stack, token);
			if (cssString == "") throw new IOException("cssProperties(): '"+token+"' not found");

			StringBuilder output = new StringBuilder();
			Map<String, String> map = parseCSSProperties(cssString);
			for (int i = 1; i < params.length; i++) {
				String value = map.get(params[i]);
				if (value != null) {
					output.append(params[i] + ":" + value + ";");
				}
			}
			return output.toString();
		}
					
		//
		// replace occurances of @cssTextProperties(token)@ with the CSS-text properties of that replacement token
		//
        private String outputCssTextProperties(Stack<String> stack, String[] params) throws IOException {
			if (params.length == 0) throw new IOException("cssTextProperties(): pass a replacement");
			String token = params[0];
			String[] newParams = {token, "color", "line-height", "text-align", "text-decoration", "white-space",
													"font", "font-family", "font-size", "font-style", "font-weight", "font-variant"
									// skipping the following properties for speed reasons (???)
									//				"direction", "letter-spacing", "text-indent", "text-shadow", "text-transform", "word-spacing"
									//				"font-size-adjust", "font-stretch",
											};
			return outputCssProperties(stack, newParams);
		}
					
		//
		// replace occurances of @cssShadow(size, color)@ with CSS to show a shadow, specific to the platform
		//
        private String outputCssShadow(Stack<String> stack, String[] params) throws IOException {
			if (isBrowser("SAFARI_3")) {
				String size = (params.length > 1 ? params[0] : "5px");
				String color = (params.length > 1 ? colorToColorString(this.getColor(stack, params[1])) : "#666666");
				return "-webkit-box-shadow:" + size + " " + color + ";";
			}
			return "";
		}
		
		//
		// replace occurances of @roundCorners(size[ size[ size[ size]]])@ with CSS to round corners, specific to the platform
		//
        private String outputRoundCorners(Stack<String> stack, String[] params) throws IOException {
			boolean isSafari3 = isBrowser("SAFARI_3");
			boolean isGecko1_8 = isBrowser("GECKO_1_8_OR_HIGHER");
			
			if (isSafari3 || isGecko1_8) {
				String propName = (isSafari3 ? "-webkit-border-radius:" : "-moz-border-radius:");
				String size = (params.length > 0 ? params[0] : "3px").toLowerCase();

				if (size.equals("") || size.equals("small")){	size = "3px";	}
				else if (size.equals("medium")) 			{	size = "5px";	}
				else if (size.equals("big"))				{	size = "10px";	}
				else if (size.equals("huge"))				{	size = "10px";	}
				return (propName + size + ";");
			}
			return "";
		}
		
		//
		// replace occurances of @opacity(percentage)@ with CSS opacity value (correct for each platform)
		//
		//	TODO: does IE7 support regular opacity?
		//
        private String outputOpacity(Stack<String> stack, String[] params) throws IOException {
			float opacity;
			try {
				opacity = (float) (Float.parseFloat(params[0]) / 100);
			} catch (Exception e) {
				throw new IOException("opacity(): pass opacity as integer percentage");
			}
			if (isBrowser("MSIE")) {
				return "filter:alpha(opacity=" + ((int)(opacity * 100)) + ");";
			} else {
				return "opacity:"+opacity+";";
			}
        }


		//
		//	given a string of CSS properties, turn them into a name:value map
		//
		private Map<String, String> parseCSSProperties(String cssString) {
			Map<String, String> map = new HashMap<String, String>();
			
			String[] props = cssString.trim().split("\\s*;\\s*");
			for (int i = 0; i < props.length; i++) {
				String[] prop = props[i].split("\\s*:\\s*");
				if (prop.length == 2) {
					map.put(prop[0], prop[1]);
				}
			}
			return map;
		}


        //
        // Private functions
        //

        private void getFiles(Document document, String ename,
                              File baseDir, List<File> list) {
            Element docElement = getFirstChildElement(document, E_SKIN);
            Element common = getFirstChildElement(docElement, E_COMMON);
            addFiles(common, ename, baseDir, list);
            Element root = getFirstChildElement(docElement, this.client);
            if (root == null && this.client.equals(SkinResources.CLIENT_ADVANCED)) {
                root = docElement;
            }
            addFiles(root, ename, baseDir, list);
        }

        private void addFiles(Element root, String ename,
                              File baseDir, List<File> list) {
            if (root == null) return;

            Element element = getFirstChildElement(root, ename);
            if (element != null) {
                Element fileEl = getFirstChildElement(element, E_FILE);
                while (fileEl != null) {
                    String filename = getChildText(fileEl);
                    File file = new File(baseDir, filename);
                    list.add(file);
                    fileEl = getNextSiblingElement(fileEl, E_FILE);
                }
            }
        }

        private static Element getFirstChildElement(Node parent, String ename) {
            Node child = parent.getFirstChild();
            while (child != null) {
                if (child.getNodeType() == Node.ELEMENT_NODE &&
                        child.getNodeName().equals(ename)) {
                    return (Element) child;
                }
                child = child.getNextSibling();
            }
            return null;
        }

        private static Element getNextSiblingElement(Node node, String ename) {
            Node sibling = node.getNextSibling();
            while (sibling != null) {
                if (sibling.getNodeType() == Node.ELEMENT_NODE &&
                        sibling.getNodeName().equals(ename)) {
                    return (Element) sibling;
                }
                sibling = sibling.getNextSibling();
            }
            return null;
        }

        private static String getChildText(Node node) {
            StringBuilder str = new StringBuilder();
            Node child = node.getFirstChild();
            while (child != null) {
                if (child.getNodeType() == Node.TEXT_NODE) {
                    str.append(child.getNodeValue());
                }
                child = child.getNextSibling();
            }
            return str.toString();
        }
    } // class Manifest

} // class SkinResources
