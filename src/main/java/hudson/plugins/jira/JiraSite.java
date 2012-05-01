package hudson.plugins.jira;

import hudson.Util;
import hudson.model.AbstractProject;
import hudson.plugins.jira.soap.JiraSoapService;
import hudson.plugins.jira.soap.JiraSoapServiceService;
import hudson.plugins.jira.soap.JiraSoapServiceServiceLocator;
import hudson.plugins.jira.soap.RemoteIssue;
import hudson.plugins.jira.soap.RemoteIssueType;
import hudson.plugins.jira.soap.RemoteVersion;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.xml.rpc.ServiceException;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Represents an external JIRA installation and configuration
 * needed to access this JIRA.
 *
 * @author Kohsuke Kawaguchi
 */
public class JiraSite {
	
    /**
     * Regexp pattern that identifies JIRA issue token.
     * If this pattern changes help pages (help-issue-pattern_xy.html) must be updated 
     * <p>
     * First char must be a letter, then at least one letter, digit or underscore.
     * See issue JENKINS-729, JENKINS-4092
     */
    protected static final Pattern DEFAULT_ISSUE_PATTERN = Pattern.compile("([a-zA-Z][a-zA-Z0-9_]+-[1-9][0-9]*)([^.]|\\.[^0-9]|\\.$|$)");
	
    /**
     * URL of JIRA, like <tt>http://jira.codehaus.org/</tt>.
     * Mandatory. Normalized to end with '/'
     */
    public final URL url;

    /**
     * User name needed to login. Optional.
     */
    public final String userName;

    /**
     * Password needed to login. Optional.
     */
    public final String password;

    /**
     * Group visibility to constrain the visibility of the added comment. Optional.
     */
    public final String groupVisibility;
    
    /**
     * Role visibility to constrain the visibility of the added comment. Optional.
     */
    public final String roleVisibility;
    
    /**
     * True if this JIRA is configured to allow Confluence-style Wiki comment.
     */
    public final boolean supportsWikiStyleComment;
    
    /**
     * to record scm changes in jira issue
     * @since 1.21
     */
    public final boolean recordScmChanges;   
    
    /**
     * user defined pattern
     * @since 1.22
     */    
    private final String userPattern;
    
    private transient Pattern userPat;
    
    /**
     * updated jira issue for all status
     * @since 1.22
     */
    public final boolean updateJiraIssueForAllStatus;
    

    /**
     * List of project keys (i.e., "MNG" portion of "MNG-512"),
     * last time we checked. Copy on write semantics.
     */
    // TODO: seems like this is never invalidated (never set to null)
    // should we implement to invalidate this (say every hour)?
    private transient volatile Set<String> projects;

    /**
     * @stapler-constructor
     */
    @DataBoundConstructor
    public JiraSite(URL url, String userName, String password, boolean supportsWikiStyleComment, boolean recordScmChanges, String userPattern, 
                    boolean updateJiraIssueForAllStatus, String groupVisibility, String roleVisibility) {
        if(!url.toExternalForm().endsWith("/"))
            try {
                url = new URL(url.toExternalForm()+"/");
            } catch (MalformedURLException e) {
                throw new AssertionError(e); // impossible
            }
        this.url = url;
        this.userName = Util.fixEmpty(userName);
        this.password = Util.fixEmpty(password);
        this.supportsWikiStyleComment = supportsWikiStyleComment;
        this.recordScmChanges = recordScmChanges;
        this.userPattern = Util.fixEmpty(userPattern);
        if (this.userPattern != null) {
        	this.userPat = Pattern.compile(this.userPattern);
        } else {
        	this.userPat = null;
        }
         
        this.updateJiraIssueForAllStatus = updateJiraIssueForAllStatus;
        this.groupVisibility = Util.fixEmpty(groupVisibility);
        this.roleVisibility = Util.fixEmpty(roleVisibility);
    }

    public String getName() {
        return url.toExternalForm();
    }

    /**
     * Creates a remote access session to this JIRA.
     *
     * @return
     *      null if remote access is not supported.
     */
    public JiraSession createSession() throws IOException, ServiceException {
        if(userName==null || password==null)
            return null;    // remote access not supported
        JiraSoapServiceService jiraSoapServiceGetter = new JiraSoapServiceServiceLocator();

        JiraSoapService service = jiraSoapServiceGetter.getJirasoapserviceV2(
            new URL(url, "rpc/soap/jirasoapservice-v2"));
        return new JiraSession(this,service,service.login(userName,password));
    }

    /**
     * Computes the URL to the given issue.
     */
    public URL getUrl(JiraIssue issue) throws IOException {
        return getUrl(issue.id);
    }

    /**
     * Computes the URL to the given issue.
     */
    public URL getUrl(String id) throws MalformedURLException {
        return new URL(url, "browse/" + id.toUpperCase());
    }
    
    /**
     * Gets the user-defined issue pattern if any.
     * 
     * @return the pattern or null
     */
    public Pattern getUserPattern() {
    	if (userPattern == null) {
    		return null;
    	}
    	
    	if (userPat == null) {
    		// We don't care about any thread race- or visibility issues here.
    		// The worst thing which could happen, is that the pattern
    		// is compiled multiple times.
    		Pattern p = Pattern.compile(userPattern);
    		userPat = p;
    	}
    	return userPat;
    }
    
    public Pattern getIssuePattern() {
    	if (getUserPattern() != null) {
    		return getUserPattern();
    	}
    	
    	return DEFAULT_ISSUE_PATTERN;
    }

    /**
     * Gets the list of project IDs in this JIRA.
     * This information could be bit old, or it can be null.
     */
    public Set<String> getProjectKeys() {
        if(projects==null) {
            synchronized (this) {
                try {
                    if(projects==null) {
                        JiraSession session = createSession();
                        if(session!=null)
                            projects = Collections.unmodifiableSet(session.getProjectKeys());
                    }
                } catch (IOException e) {
                    // in case of error, set empty set to avoid trying the same thing repeatedly.
                    LOGGER.log(Level.WARNING,"Failed to obtain JIRA project list",e);
                } catch (ServiceException e) {
                    LOGGER.log(Level.WARNING,"Failed to obtain JIRA project list",e);
                }
            }
        }
        // fall back to empty if failed to talk to the server
        if(projects==null) {
            return Collections.emptySet();
        }
        
        return projects;
    }

    /**
     * Gets the effective {@link JiraSite} associated with the given project.
     *
     * @return null
     *      if no such was found.
     */
    public static JiraSite get(AbstractProject<?,?> p) {
        JiraProjectProperty jpp = p.getProperty(JiraProjectProperty.class);
        if(jpp!=null) {
            JiraSite site = jpp.getSite();
            if(site!=null)
                return site;
        }

        // none is explicitly configured. try the default ---
        // if only one is configured, that must be it.
        JiraSite[] sites = JiraProjectProperty.DESCRIPTOR.getSites();
        if(sites.length==1) return sites[0];

        return null;
    }

    /**
     * Checks if the given JIRA id will be likely to exist in this issue tracker.
     *
     * <p>
     * This method checks whether the key portion is a valid key (except that
     * it can potentially use stale data). Number portion is not checked at all.
     *
     * @param id
     *      String like MNG-1234
     */
    public boolean existsIssue(String id) {
        int idx = id.indexOf('-');
        if(idx==-1) return false;

        Set<String> keys = getProjectKeys();
        return keys.contains(id.substring(0,idx).toUpperCase());
    }
    
    /**
     * Returns the remote issue with the given id or <code>null</code> if it wasn't found.
     */
    public JiraIssue getIssue(String id) throws IOException, ServiceException {
        JiraSession session = createSession();
        if (session != null) {
            RemoteIssue remoteIssue = session.getIssue(id);
            if (remoteIssue != null) {
                return new JiraIssue(remoteIssue);
            }
        }
        return null;
    }
    
    /**
     * Release a given version.
     * 
     * @param projectKey The Project Key
     * @param versionName The name of the version
     * @throws IOException
     * @throws ServiceException
     */
    public void releaseVersion(String projectKey, String versionName) throws IOException, ServiceException {
        JiraSession session = createSession();
        if (session != null) {
            RemoteVersion[] versions = session.getVersions(projectKey);
            if(versions == null ) return;
            for( RemoteVersion version : versions ) {
            	if(version.getName().equals(versionName)) {
            		version.setReleased(true);
            		session.releaseVersion(projectKey,version);
            		return;
            	}
            }
        }
    }
    
    /**
     * Returns all versions for the given project key.
     * 
     * @param projectKey Project Key
     * @return A set of JiraVersions
     * @throws IOException
     * @throws ServiceException
     */
    public Set<JiraVersion> getVersions(String projectKey) throws IOException, ServiceException {
    	JiraSession session = createSession();
    	if(session == null) return Collections.emptySet();
    	
    	RemoteVersion[] versions = session.getVersions(projectKey);
    	
    	if(versions == null ) return Collections.emptySet();
    	
    	Set<JiraVersion> versionsSet = new HashSet<JiraVersion>(versions.length);
    			
    	for( int i = 0; i < versions.length; ++i) {
    		RemoteVersion version = versions[i];
    		versionsSet.add(new JiraVersion(version));
    	}
    	
    	return versionsSet;
    }
    
    /**
     * Generates release notes for a given version.
     * 
     * @param projectKey
     * @param versionName
     * @return release notes
     * @throws IOException
     * @throws ServiceException
     */
    public String getReleaseNotesForFixVersion(String projectKey, String versionName) throws IOException, ServiceException {
    	JiraSession session = createSession();
    	if(session == null) return "";
    	
    	RemoteIssue[] issues = session.getIssuesWithFixVersion(projectKey, versionName);
    	RemoteIssueType[] types = session.getIssueTypes();
    	
    	HashMap<String,String> typeNameMap = new HashMap<String,String>();
    	
    	for( RemoteIssueType type : types ) {
    		typeNameMap.put(type.getId(), type.getName());
    	}
    	    	
    	if(issues == null ) return "";

    	Map<String, Set<String>> releaseNotes = new HashMap<String,Set<String>>();
    	
    	for( int i = 0; i < issues.length; ++i ) {
    		RemoteIssue issue = issues[i];
    		String key = issue.getKey();
    		String summary =  issue.getSummary();
    		String type = "UNKNOWN";
    		
    		if( typeNameMap.containsKey(issue.getType())) {
    			type = typeNameMap.get(issue.getType());
    		}
    		
    		Set<String> issueSet;
    		if( !releaseNotes.containsKey(type)) {
    			issueSet = new HashSet<String>();
    			releaseNotes.put(type, issueSet);
    		} else {
    			issueSet = releaseNotes.get(type);
    		}
    		
    		issueSet.add(String.format(" - [%s] %s",key,summary));
    	}
    	
    	StringBuilder sb = new StringBuilder();
    	for( String type : releaseNotes.keySet() ) {
    		sb.append(String.format("# %s\n",type));
    		for(String issue : releaseNotes.get(type)) {
    			sb.append(issue);
    			sb.append("\n");
    		}
    	}
    	
    	return sb.toString();
    }
    
    /**
     * Gets a set of issues that have the given fixVersion associated with them.
     * 
     * @param projectKey The project key
     * @param versionName The fixVersion
     * @return A set of JiraIssues
     * @throws IOException
     * @throws ServiceException
     */
    public Set<JiraIssue> getIssueWithFixVersion(String projectKey, String versionName) throws IOException, ServiceException {
    	JiraSession session = createSession();
    	if(session == null) return Collections.emptySet();
    	
    	RemoteIssue[] issues = session.getIssuesWithFixVersion(projectKey, versionName);
    	    	
    	if(issues == null ) return Collections.emptySet();
    	
    	Set<JiraIssue> issueSet = new HashSet<JiraIssue>(issues.length);
    			
    	for( int i = 0; i < issues.length; ++i) {
    		RemoteIssue issue = issues[i];
    		issueSet.add(new JiraIssue(issue));
    	}
    	
    	return issueSet;
    }
    
    /**
     * Migrates issues matching the jql query provided to a new fix version.
     * 
     * @param projectKey The project key
     * @param versionName The new fixVersion
     * @param query A JQL Query
     * @throws IOException
     * @throws ServiceException
     */
    public void migrateIssuesToFixVersion(String projectKey, String versionName, String query) throws IOException, ServiceException {
    	JiraSession session = createSession();
    	if(session == null) return;
    	
    	session.migrateIssuesToFixVersion(projectKey, versionName, query);
    }
    
    private static final Logger LOGGER = Logger.getLogger(JiraSite.class.getName());
}
