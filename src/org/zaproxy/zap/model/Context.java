/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Copyright 2012 The ZAP Development Team
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.model;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.URI;
import org.apache.log4j.Logger;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.Session;
import org.parosproxy.paros.model.SiteNode;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.authentication.AuthenticationMethod;
import org.zaproxy.zap.authentication.ManualAuthenticationMethodType.ManualAuthenticationMethod;
import org.zaproxy.zap.extension.authorization.AuthorizationDetectionMethod;
import org.zaproxy.zap.extension.authorization.BasicAuthorizationDetectionMethod;
import org.zaproxy.zap.extension.authorization.BasicAuthorizationDetectionMethod.LogicalOperator;
import org.zaproxy.zap.session.CookieBasedSessionManagementMethodType.CookieBasedSessionManagementMethod;
import org.zaproxy.zap.session.SessionManagementMethod;

public class Context {

	private static Logger log = Logger.getLogger(Context.class);

	private Session session;
	private int index;
	private String name;
	private String description = "";

	private List<String> includeInRegexs = new ArrayList<>();
	private List<String> excludeFromRegexs = new ArrayList<>();
	private List<Pattern> includeInPatterns = new ArrayList<>();
	private List<Pattern> excludeFromPatterns = new ArrayList<>();

	/** The authentication method. */
	private AuthenticationMethod authenticationMethod = null;

	/** The session management method. */
	private SessionManagementMethod sessionManagementMethod;

	/** The authorization detection method used for this context. */
	private AuthorizationDetectionMethod authorizationDetectionMethod;
	
	private TechSet techSet = new TechSet(Tech.builtInTech);
	private boolean inScope = true;
	private ParameterParser urlParamParser = new StandardParameterParser();
	private ParameterParser postParamParser = new StandardParameterParser();

	public Context(Session session, int index) {
		this.session = session;
		this.index = index;
		this.name = String.valueOf(index);
		this.sessionManagementMethod = new CookieBasedSessionManagementMethod(index);
		this.authenticationMethod = new ManualAuthenticationMethod(index);
		this.authorizationDetectionMethod = new BasicAuthorizationDetectionMethod(null, null, null,
				LogicalOperator.AND);
	}

	public boolean isIncludedInScope(SiteNode sn) {
		if (!this.inScope) {
			return false;
		}
		return this.isIncluded(sn);
	}

	public boolean isIncluded(SiteNode sn) {
		if (sn == null) {
			return false;
		}
		return isIncluded(sn.getHierarchicNodeName());
	}

	/*
	 * Not needed right now, but may be needed in the future? public boolean
	 * isExplicitlyIncluded(SiteNode sn) { if (sn == null) { return false; } return
	 * isExplicitlyIncluded(sn.getHierarchicNodeName()); }
	 * 
	 * public boolean isExplicitlyIncluded(String url) { if (url == null) { return false; } try {
	 * return this.includeInPatterns.contains(this.getPatternUrl(url, false)) ||
	 * this.includeInPatterns.contains(this.getPatternUrl(url, false)); } catch (Exception e) {
	 * return false; } }
	 */

	public boolean isIncluded(String url) {
		if (url == null) {
			return false;
		}
		if (url.indexOf("?") > 0) {
			// Strip off any parameters
			url = url.substring(0, url.indexOf("?"));
		}
		for (Pattern p : this.includeInPatterns) {
			if (p.matcher(url).matches()) {
				return true;
			}
		}
		return false;
	}

	public boolean isExcludedFromScope(SiteNode sn) {
		if (!this.inScope) {
			return false;
		}
		return this.isExcluded(sn);
	}

	public boolean isExcluded(SiteNode sn) {
		if (sn == null) {
			return false;
		}
		return isExcluded(sn.getHierarchicNodeName());
	}

	public boolean isExcluded(String url) {
		if (url == null) {
			return false;
		}
		if (url.indexOf("?") > 0) {
			// Strip off any parameters
			url = url.substring(0, url.indexOf("?"));
		}
		for (Pattern p : this.excludeFromPatterns) {
			if (p.matcher(url).matches()) {
				return true;
			}
		}
		return false;
	}

	public boolean isInContext(HistoryReference href) {
		if (href == null) {
			return false;
		}
		if (href.getSiteNode() != null) {
			return this.isInContext(href.getSiteNode());
		}
		try {
			return this.isInContext(href.getURI().toString());
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		return false;
	}

	public boolean isInContext(SiteNode sn) {
		if (sn == null) {
			return false;
		}
		return isInContext(sn.getHierarchicNodeName());
	}

	public boolean isInContext(String url) {
		if (url.indexOf("?") > 0) {
			// String off any parameters
			url = url.substring(0, url.indexOf("?"));
		}
		if (!this.isIncluded(url)) {
			// Not explicitly included
			return false;
		}
		// Check to see if its explicitly excluded
		return !this.isExcluded(url);
	}

	/**
	 * Gets the nodes from the site tree which are "In Scope". Searches recursively starting from
	 * the root node. Should be used with care, as it is time-consuming, querying the database for
	 * every node in the Site Tree.
	 * 
	 * @return the nodes in scope from site tree
	 */
	public List<SiteNode> getNodesInContextFromSiteTree() {
		List<SiteNode> nodes = new LinkedList<>();
		SiteNode rootNode = (SiteNode) session.getSiteTree().getRoot();
		fillNodesInContext(rootNode, nodes);
		return nodes;
	}

	/**
	 * Fills a given list with nodes in scope, searching recursively.
	 * 
	 * @param rootNode the root node
	 * @param nodesList the nodes list
	 */
	private void fillNodesInContext(SiteNode rootNode, List<SiteNode> nodesList) {
		@SuppressWarnings("unchecked")
		Enumeration<SiteNode> en = rootNode.children();
		while (en.hasMoreElements()) {
			SiteNode sn = en.nextElement();
			if (isInContext(sn)) {
				nodesList.add(sn);
			}
			fillNodesInContext(sn, nodesList);
		}
	}

	public List<String> getIncludeInContextRegexs() {
		return includeInRegexs;
	}

	private void checkRegexs(List<String> regexs) throws Exception {
		for (String url : regexs) {
			url = url.trim();
			if (url.length() > 0) {
				Pattern.compile(url, Pattern.CASE_INSENSITIVE);
			}
		}
	}

	public void setIncludeInContextRegexs(List<String> includeRegexs) throws Exception {
		// Check they are all valid regexes first
		checkRegexs(includeRegexs);
		// Check if theyve been changed
		if (includeInRegexs.size() == includeRegexs.size()) {
			boolean changed = false;
			for (int i = 0; i < includeInRegexs.size(); i++) {
				if (!includeInRegexs.get(i).equals(includeRegexs.get(i))) {
					changed = true;
					break;
				}
			}
			if (!changed) {
				// No point reapplying the same regexs
				return;
			}
		}
		includeInRegexs.clear();
		includeInPatterns.clear();
		for (String url : includeRegexs) {
			url = url.trim();
			if (url.length() > 0) {
				Pattern p = Pattern.compile(url, Pattern.CASE_INSENSITIVE);
				includeInRegexs.add(url);
				includeInPatterns.add(p);
			}
		}
	}

	private String getPatternFromNode(SiteNode sn, boolean recurse) throws Exception {
		return this.getPatternUrl(new URI(sn.getHierarchicNodeName(), false).toString(), recurse);
	}

	private String getPatternUrl(String url, boolean recurse) throws Exception {
		if (url.indexOf("?") > 0) {
			// Strip off any parameters
			url = url.substring(0, url.indexOf("?"));
		}

		if (recurse) {
			url = Pattern.quote(url) + ".*";
		} else {
			url = Pattern.quote(url);
		}
		return url;
	}

	public void excludeFromContext(SiteNode sn, boolean recurse) throws Exception {
		addExcludeFromContextRegex(this.getPatternFromNode(sn, recurse));
	}

	public void addIncludeInContextRegex(String includeRegex) {
		Pattern p = Pattern.compile(includeRegex, Pattern.CASE_INSENSITIVE);
		includeInPatterns.add(p);
		includeInRegexs.add(includeRegex);
	}

	public List<String> getExcludeFromContextRegexs() {
		return excludeFromRegexs;
	}

	public void setExcludeFromContextRegexs(List<String> excludeRegexs) throws Exception {
		// Check they are all valid regexes first
		checkRegexs(excludeRegexs);
		// Check if theyve been changed
		if (excludeFromRegexs.size() == excludeRegexs.size()) {
			boolean changed = false;
			for (int i = 0; i < excludeFromRegexs.size(); i++) {
				if (!excludeFromRegexs.get(i).equals(excludeRegexs.get(i))) {
					changed = true;
					break;
				}
			}
			if (!changed) {
				// No point reapplying the same regexs
				return;
			}
		}

		excludeFromRegexs.clear();
		excludeFromPatterns.clear();
		for (String url : excludeRegexs) {
			url = url.trim();
			if (url.length() > 0) {
				Pattern p = Pattern.compile(url, Pattern.CASE_INSENSITIVE);
				excludeFromPatterns.add(p);
				excludeFromRegexs.add(url);
			}
		}
	}

	public void addExcludeFromContextRegex(String excludeRegex) {
		Pattern p = Pattern.compile(excludeRegex, Pattern.CASE_INSENSITIVE);
		excludeFromPatterns.add(p);
		excludeFromRegexs.add(excludeRegex);
	}

	public void save() {
		this.session.saveContext(this);
	}

	public TechSet getTechSet() {
		return techSet;
	}

	public void setTechSet(TechSet techSet) {
		this.techSet = techSet;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public int getIndex() {
		return this.index;
	}

	public boolean isInScope() {
		return inScope;
	}

	public void setInScope(boolean inScope) {
		this.inScope = inScope;
	}

	/**
	 * Gets the authentication method corresponding to this context.
	 * 
	 * @return the authentication method
	 */
	public AuthenticationMethod getAuthenticationMethod() {
		return authenticationMethod;
	}

	/**
	 * Sets the authentication method corresponding to this context.
	 * 
	 * @param authenticationMethod the new authentication method
	 */
	public void setAuthenticationMethod(AuthenticationMethod authenticationMethod) {
		this.authenticationMethod = authenticationMethod;
	}

	/**
	 * Gets the session management method corresponding to this context.
	 * 
	 * @return the session management method
	 */
	public SessionManagementMethod getSessionManagementMethod() {
		return sessionManagementMethod;
	}

	/**
	 * Sets the session management method corresponding to this context.
	 * 
	 * @param sessionManagementMethod the new session management method
	 */
	public void setSessionManagementMethod(SessionManagementMethod sessionManagementMethod) {
		this.sessionManagementMethod = sessionManagementMethod;
	}

	/**
	 * Gets the authorization detection method corresponding to this context.
	 * 
	 * @return the authorization detection method
	 */
	public AuthorizationDetectionMethod getAuthorizationDetectionMethod() {
		return authorizationDetectionMethod;
	}

	/**
	 * Sets the authorization detection method corresponding to this context.
	 * 
	 * @param authorizationDetectionMethod the new authorization detectionmethod
	 */
	public void setAuthorizationDetectionMethod(AuthorizationDetectionMethod authorizationDetectionMethod) {
		this.authorizationDetectionMethod = authorizationDetectionMethod;
	}
	
	public ParameterParser getUrlParamParser() {
		return urlParamParser;
	}

	public void setUrlParamParser(ParameterParser paramParser) {
		this.urlParamParser = paramParser;
		restructureSiteTree();
	}

	public ParameterParser getPostParamParser() {
		return postParamParser;
	}

	public void setPostParamParser(ParameterParser postParamParser) {
		this.postParamParser = postParamParser;
	}

	public void restructureSiteTree() {
        if (EventQueue.isDispatchThread()) {
        	restructureSiteTreeEventHandler();
        } else {
            try {
                EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                    	restructureSiteTreeEventHandler();
                    }
                });
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
	}
	
	private void restructureSiteTreeEventHandler() {
		log.debug("Restructure site tree for context: " + this.getName());
		
		HttpMessage msg;
		SiteNode sn2;

		for (SiteNode sn: this.getNodesInContextFromSiteTree()) {
			log.debug("Restructure site tree, node: " + sn.getNodeName());
			try {
				msg = sn.getHistoryReference().getHttpMessage();
				if (msg != null) {
					sn2 = session.getSiteTree().findNode(msg, sn.getChildCount() > 0);
					if (sn2 == null) {
						sn2 = session.getSiteTree().addPath(sn.getHistoryReference(), msg);
					}
						
					if (! sn2.equals(sn)) {
						// TODO: Might be better in a 'merge'? Do we need to copy other things, list custom icons? 
						for (Alert alert : sn.getAlerts()) {
							sn2.addAlert(alert);
						}
						for (Alert alert : sn.getAlerts()) {
							sn.deleteAlert(alert);
						}
						session.getSiteTree().removeNodeFromParent(sn);
					}
				}
			} catch (Exception e) {
				log.error(e.getMessage(), e);
			}
		}
	}

	/**
	 * Creates a copy of the Context. The copy is deep, with the exception of the TechSet.
	 * 
	 * @return the context
	 */
	public Context duplicate() {
		Context newContext = new Context(session, getIndex());
		newContext.description = this.description;
		newContext.name = this.name;
		newContext.includeInRegexs = new ArrayList<>(this.includeInRegexs);
		newContext.includeInPatterns = new ArrayList<>(this.includeInPatterns);
		newContext.excludeFromRegexs = new ArrayList<>(this.excludeFromRegexs);
		newContext.excludeFromPatterns = new ArrayList<>(this.excludeFromPatterns);
		newContext.inScope = this.inScope;
		newContext.techSet = new TechSet(this.techSet);
		newContext.authenticationMethod = this.authenticationMethod.clone();
		newContext.sessionManagementMethod = this.sessionManagementMethod.clone();
		newContext.urlParamParser = this.urlParamParser.clone();
		newContext.postParamParser = this.postParamParser.clone();
		newContext.authorizationDetectionMethod = this.authorizationDetectionMethod.clone();
		return newContext;
	}

}
