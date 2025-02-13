/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.site.deploy;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.maven.doxia.site.inheritance.URIPathDescriptor;
import org.apache.maven.doxia.tools.SiteTool;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.Site;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.site.AbstractSiteMojo;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.provider.ScmUrlUtils;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.apache.maven.wagon.CommandExecutionException;
import org.apache.maven.wagon.CommandExecutor;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.observers.Debug;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.repository.Repository;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

/**
 * Abstract base class for deploy mojos.
 * Since 2.3 this includes {@link SiteStageMojo} and {@link SiteStageDeployMojo}.
 *
 * @author ltheussl
 * @since 2.3
 */
public abstract class AbstractDeployMojo extends AbstractSiteMojo {
    /**
     * Directory containing the generated project sites and report distributions.
     *
     * @since 2.3
     */
    @Parameter(alias = "outputDirectory", defaultValue = "${project.reporting.outputDirectory}", required = true)
    private File inputDirectory;

    /**
     * Whether to run the "chmod" command on the remote site after the deploy.
     * Defaults to "true".
     *
     * @since 2.1
     */
    @Parameter(property = "maven.site.chmod", defaultValue = "true")
    private boolean chmod;

    /**
     * The mode used by the "chmod" command. Only used if chmod = true.
     * Defaults to "g+w,a+rX".
     *
     * @since 2.1
     */
    @Parameter(property = "maven.site.chmod.mode", defaultValue = "g+w,a+rX")
    private String chmodMode;

    /**
     * The options used by the "chmod" command. Only used if chmod = true.
     * Defaults to "-Rf".
     *
     * @since 2.1
     */
    @Parameter(property = "maven.site.chmod.options", defaultValue = "-Rf")
    private String chmodOptions;

    /**
     * Set this to 'true' to skip site deployment.
     *
     * @since 3.0
     */
    @Parameter(property = "maven.site.deploy.skip", defaultValue = "false")
    private boolean skipDeploy;

    /**
     * The current user system settings for use in Maven.
     */
    @Parameter(defaultValue = "${settings}", readonly = true)
    private Settings settings;

    /**
     * @since 3.0-beta-2
     */
    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession mavenSession;

    private String topDistributionManagementSiteUrl;

    private Site deploySite;

    @Component
    private PlexusContainer container;

    @Component
    SettingsDecrypter settingsDecrypter;

    /**
     * {@inheritDoc}
     */
    public void execute() throws MojoExecutionException {
        if (skip && isDeploy()) {
            getLog().info("maven.site.skip = true: Skipping site deployment");
            return;
        }

        if (skipDeploy && isDeploy()) {
            getLog().info("maven.site.deploy.skip = true: Skipping site deployment");
            return;
        }

        deployTo(new Repository(getDeploySite().getId(), getDeploySite().getUrl()));
    }

    /**
     * Make sure the given URL ends with a slash.
     *
     * @param url a String
     * @return if url already ends with '/' it is returned unchanged.
     *         Otherwise a '/' character is appended.
     */
    protected static String appendSlash(final String url) {
        if (url.endsWith("/")) {
            return url;
        } else {
            return url + "/";
        }
    }

    /**
     * Detect if the mojo is staging or deploying.
     *
     * @return <code>true</code> if the mojo is for deploy and not staging (local or deploy)
     */
    protected abstract boolean isDeploy();

    /**
     * Get the top distribution management site url, used for module relative path calculations.
     * This should be a top-level URL, ie above modules and locale sub-directories. Each deploy mojo
     * can tweak algorithm to determine this top site by implementing determineTopDistributionManagementSiteUrl().
     *
     * @return the site for deployment
     * @throws MojoExecutionException in case of issue
     * @see #determineTopDistributionManagementSiteUrl()
     */
    protected String getTopDistributionManagementSiteUrl() throws MojoExecutionException {
        if (topDistributionManagementSiteUrl == null) {
            topDistributionManagementSiteUrl = determineTopDistributionManagementSiteUrl();

            if (!isDeploy()) {
                getLog().debug("distributionManagement.site.url relative path: " + getDeployModuleDirectory());
            }
        }
        return topDistributionManagementSiteUrl;
    }

    protected abstract String determineTopDistributionManagementSiteUrl() throws MojoExecutionException;

    /**
     * Get the site used for deployment, with its id to look up credential settings and the target URL for the deploy.
     * This should be a top-level URL, ie above modules and locale sub-directories. Each deploy mojo
     * can tweak algorithm to determine this deploy site by implementing determineDeploySite().
     *
     * @return the site for deployment
     * @throws MojoExecutionException in case of issue
     * @see #determineDeploySite()
     */
    protected Site getDeploySite() throws MojoExecutionException {
        if (deploySite == null) {
            deploySite = determineDeploySite();
        }
        return deploySite;
    }

    protected abstract Site determineDeploySite() throws MojoExecutionException;

    /**
     * Find the relative path between the distribution URLs of the top site and the current project.
     *
     * @return the relative path or "./" if the two URLs are the same.
     * @throws MojoExecutionException in case of issue
     */
    protected String getDeployModuleDirectory() throws MojoExecutionException {
        String to = getSite(project).getUrl();

        getLog().debug("Mapping url source calculation: ");
        String from = getTopDistributionManagementSiteUrl();

        String relative = siteTool.getRelativePath(to, from);

        // SiteTool.getRelativePath() uses File.separatorChar,
        // so we need to convert '\' to '/' in order for the URL to be valid for Windows users
        relative = relative.replace('\\', '/');

        return ("".equals(relative)) ? "./" : relative;
    }

    /**
     * Use wagon to deploy the generated site to a given repository.
     *
     * @param repository the repository to deploy to.
     *                   This needs to contain a valid, non-null {@link Repository#getId() id}
     *                   to look up credentials for the deploy, and a valid, non-null
     *                   {@link Repository#getUrl() scm url} to deploy to.
     * @throws MojoExecutionException if the deploy fails.
     */
    private void deployTo(final Repository repository) throws MojoExecutionException {
        if (!inputDirectory.exists()) {
            throw new MojoExecutionException("The site does not exist, please run site:site first");
        }

        if (getLog().isDebugEnabled()) {
            getLog().debug("Deploying to '" + repository.getUrl() + "',\n    Using credentials from server id '"
                    + repository.getId() + "'");
        }

        deploy(inputDirectory, repository);
    }

    private void deploy(final File directory, final Repository repository) throws MojoExecutionException {
        // TODO: work on moving this into the deployer like the other deploy methods
        final Wagon wagon = getWagon(repository);

        try {
            ProxyInfo proxyInfo = getProxy(repository, settingsDecrypter);

            push(directory, repository, wagon, proxyInfo, getLocales(), getDeployModuleDirectory());

            if (chmod) {
                chmod(wagon, repository, chmodOptions, chmodMode);
            }
        } finally {
            try {
                wagon.disconnect();
            } catch (ConnectionException e) {
                getLog().error("Error disconnecting wagon - ignored", e);
            }
        }
    }

    private Wagon getWagon(final Repository repository) throws MojoExecutionException {
        String protocol = repository.getProtocol();
        if (protocol == null) {
            throw new MojoExecutionException("Unspecified protocol");
        }
        try {
            Wagon wagon = container.lookup(Wagon.class, protocol.toLowerCase(Locale.ROOT));
            if (!wagon.supportsDirectoryCopy()) {
                throw new MojoExecutionException(
                        "Wagon protocol '" + repository.getProtocol() + "' doesn't support directory copying");
            }
            return wagon;
        } catch (ComponentLookupException e) {
            throw new MojoExecutionException("Cannot find wagon which supports the requested protocol: " + protocol, e);
        }
    }

    public AuthenticationInfo getAuthenticationInfo(String id) {
        if (id != null) {
            List<Server> servers = settings.getServers();

            if (servers != null) {
                for (Server server : servers) {
                    if (id.equalsIgnoreCase(server.getId())) {
                        SettingsDecryptionResult result =
                                settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
                        server = result.getServer();

                        AuthenticationInfo authInfo = new AuthenticationInfo();
                        authInfo.setUserName(server.getUsername());
                        authInfo.setPassword(server.getPassword());
                        authInfo.setPrivateKey(server.getPrivateKey());
                        authInfo.setPassphrase(server.getPassphrase());

                        return authInfo;
                    }
                }
            }
        }

        return null;
    }

    private void push(
            final File inputDirectory,
            final Repository repository,
            final Wagon wagon,
            final ProxyInfo proxyInfo,
            final List<Locale> localesList,
            final String relativeDir)
            throws MojoExecutionException {
        AuthenticationInfo authenticationInfo = getAuthenticationInfo(repository.getId());
        if (authenticationInfo != null) {
            getLog().debug("authenticationInfo with id '" + repository.getId() + "'");
        }

        try {
            if (getLog().isDebugEnabled()) {
                Debug debug = new Debug();

                wagon.addSessionListener(debug);

                wagon.addTransferListener(debug);
            }

            if (proxyInfo != null) {
                getLog().debug("connect with proxyInfo");
                wagon.connect(repository, authenticationInfo, proxyInfo);
            } else if (authenticationInfo != null) {
                getLog().debug("connect with authenticationInfo and without proxyInfo");
                wagon.connect(repository, authenticationInfo);
            } else {
                getLog().debug("connect without authenticationInfo and without proxyInfo");
                wagon.connect(repository);
            }

            getLog().info("Pushing " + inputDirectory);

            for (Locale locale : localesList) {
                if (!locale.equals(SiteTool.DEFAULT_LOCALE)) {
                    getLog().info("   >>> to " + appendSlash(repository.getUrl()) + locale + "/" + relativeDir);

                    wagon.putDirectory(new File(inputDirectory, locale.toString()), locale + "/" + relativeDir);
                } else {
                    // TODO: this also uploads the non-default locales,
                    // is there a way to exclude directories in wagon?
                    getLog().info("   >>> to " + appendSlash(repository.getUrl()) + relativeDir);

                    wagon.putDirectory(inputDirectory, relativeDir);
                }
            }
        } catch (ResourceDoesNotExistException
                | TransferFailedException
                | AuthorizationException
                | ConnectionException
                | AuthenticationException e) {
            throw new MojoExecutionException("Error uploading site", e);
        }
    }

    private static void chmod(
            final Wagon wagon, final Repository repository, final String chmodOptions, final String chmodMode)
            throws MojoExecutionException {
        try {
            if (wagon instanceof CommandExecutor) {
                CommandExecutor exec = (CommandExecutor) wagon;
                exec.executeCommand("chmod " + chmodOptions + " " + chmodMode + " " + repository.getBasedir());
            }
            // else ? silently ignore, FileWagon is not a CommandExecutor!
        } catch (CommandExecutionException e) {
            throw new MojoExecutionException("Error uploading site", e);
        }
    }

    /**
     * Get proxy information.
     *
     * @param repository        the Repository to extract the ProxyInfo from
     * @param settingsDecrypter settings password decrypter
     * @return a ProxyInfo object instantiated or <code>null</code> if no matching proxy is found.
     */
    private ProxyInfo getProxy(Repository repository, SettingsDecrypter settingsDecrypter) {
        String protocol = repository.getProtocol();
        String url = repository.getUrl();

        getLog().debug("repository protocol " + protocol);

        String originalProtocol = protocol;
        // olamy: hackish here protocol (wagon hint in fact !) is dav
        // but the real protocol (transport layer) is http(s)
        // and it's the one use in wagon to find the proxy arghhh
        // so we will check both
        if ("dav".equalsIgnoreCase(protocol) && url.startsWith("dav:")) {
            url = url.substring(4);
            if (url.startsWith("http")) {
                try {
                    URL urlSite = new URL(url);
                    protocol = urlSite.getProtocol();
                    getLog().debug("found dav protocol so transform to real transport protocol " + protocol);
                } catch (MalformedURLException e) {
                    getLog().warn("fail to build URL with " + url);
                }
            }
        } else {
            getLog().debug("getProxy 'protocol': " + protocol);
        }

        if (mavenSession != null && protocol != null) {
            MavenExecutionRequest request = mavenSession.getRequest();

            if (request != null) {
                List<Proxy> proxies = request.getProxies();

                if (proxies != null) {
                    for (Proxy proxy : proxies) {
                        if (proxy.isActive()
                                && (protocol.equalsIgnoreCase(proxy.getProtocol())
                                        || originalProtocol.equalsIgnoreCase(proxy.getProtocol()))) {
                            SettingsDecryptionResult result =
                                    settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(proxy));
                            proxy = result.getProxy();

                            ProxyInfo proxyInfo = new ProxyInfo();
                            proxyInfo.setHost(proxy.getHost());
                            // so hackish for wagon the protocol is https for site dav:
                            // dav:https://dav.codehaus.org/mojo/
                            proxyInfo.setType(protocol); // proxy.getProtocol() );
                            proxyInfo.setPort(proxy.getPort());
                            proxyInfo.setNonProxyHosts(proxy.getNonProxyHosts());
                            proxyInfo.setUserName(proxy.getUsername());
                            proxyInfo.setPassword(proxy.getPassword());

                            getLog().debug("found proxyInfo "
                                    + ("host:port " + proxyInfo.getHost() + ":" + proxyInfo.getPort() + ", "
                                            + proxyInfo.getUserName()));

                            return proxyInfo;
                        }
                    }
                }
            }
        }
        getLog().debug("getProxy 'protocol': " + protocol + " no ProxyInfo found");
        return null;
    }

    /**
     * Extract the distributionManagement site from the given MavenProject.
     *
     * @param project the MavenProject. Not null.
     * @return the project site. Not null.
     *         Also site.getUrl() and site.getId() are guaranteed to be not null.
     * @throws MojoExecutionException if any of the site info is missing.
     */
    protected static Site getSite(final MavenProject project) throws MojoExecutionException {
        final DistributionManagement distributionManagement = project.getDistributionManagement();

        if (distributionManagement == null) {
            throw new MojoExecutionException("Missing distribution management in project " + getFullName(project));
        }

        final Site site = distributionManagement.getSite();

        if (site == null) {
            throw new MojoExecutionException(
                    "Missing site information in the distribution management of the project " + getFullName(project));
        }

        if (site.getUrl() == null || site.getId() == null) {
            throw new MojoExecutionException(
                    "Missing site data: specify url and id for project " + getFullName(project));
        }

        return site;
    }

    private static String getFullName(MavenProject project) {
        return project.getName() + " (" + project.getGroupId() + ':' + project.getArtifactId() + ':'
                + project.getVersion() + ')';
    }

    /**
     * Extract the distributionManagement site of the top level parent of the given MavenProject.
     * This climbs up the project hierarchy and returns the site of the last project
     * for which {@link #getSite(org.apache.maven.project.MavenProject)} returns a site that resides in the
     * same site. Notice that it doesn't take into account if the parent is in the reactor or not.
     *
     * @param project the MavenProject. Not <code>null</code>.
     * @return the top level site. Not <code>null</code>.
     *         Also site.getUrl() and site.getId() are guaranteed to be not <code>null</code>.
     * @throws MojoExecutionException if no site info is found in the tree.
     * @see URIPathDescriptor#sameSite(java.net.URI)
     */
    protected MavenProject getTopLevelProject(MavenProject project) throws MojoExecutionException {
        Site site = getSite(project);

        MavenProject parent = project;

        while (parent.getParent() != null) {
            MavenProject oldProject = parent;
            // MSITE-585, MNG-1943
            parent = parent.getParent();

            Site oldSite = site;

            try {
                site = getSite(parent);
            } catch (MojoExecutionException e) {
                return oldProject;
            }

            try {
                if (!isSameSite(site.getUrl(), oldSite.getUrl())) {
                    return oldProject;
                }
            } catch (IllegalArgumentException e) {
                getLog().warn("Failed to parse distributionManagement.site.url of project \"" + getFullName(oldProject)
                        + "\" or project \"" + getFullName(parent) + "\": " + e.getMessage());
                return oldProject;
            }
        }
        return parent;
    }

    /**
     * Returns {@code true} if the URIs are probably pointing to the same site which means
     * <ul>
     * <li>both arguments are hierarchical URIs,</li>
     * <li>both arguments share the same host and</li>
     * <li>the path of the latter URI is a subpath of the first URI</li>
     * </ul>.
     * @param parentUri
     * @param uri
     * @return {@code true} if the URIs are probably pointing to the same site
     * @throws IllegalArgumentException if the given URIs cannot be parsed
     */
    static boolean isSameSite(String parentUri, String uri) {
        // this just normalizes the paths in it
        URIPathDescriptor siteURI =
                new URIPathDescriptor(URIEncoder.encodeURI(extractProviderSpecificPartFromScmUri(parentUri)), "");
        URIPathDescriptor oldSiteURI =
                new URIPathDescriptor(URIEncoder.encodeURI(extractProviderSpecificPartFromScmUri(uri)), "");
        // compare host and path (port and scheme should not matter)
        return isSameSite(siteURI.getBaseURI(), oldSiteURI.getBaseURI());
    }

    private static boolean isSameSite(URI parentUri, URI uri) {
        // host must be equal
        if (!Objects.equals(uri.getHost(), parentUri.getHost())) {
            return false;
        }
        // path must be a subpath
        if (uri.getPath() == null
                || parentUri.getPath() == null
                || !uri.getPath().startsWith(parentUri.getPath())) {
            return false;
        }
        return true;
    }

    /**
     * Unwraps <a href="https://maven.apache.org/scm/scm-url-format.html">SCM URLs</a> to get the provider specific part.
     * @param uri
     * @return the provider specific part if the given URI is a SCM URI, otherwise just the uri
     *
     */
    static String extractProviderSpecificPartFromScmUri(String uri) {
        if (ScmUrlUtils.isValid(uri)) {
            return ScmUrlUtils.getProviderSpecificPart(uri);
        } else {
            return uri;
        }
    }

    private static class URIEncoder {
        private static final String MARK = "-_.!~*'()";
        private static final String RESERVED = ";/?:@&=+$,";

        private static String encodeURI(final String uriString) {
            final char[] chars = uriString.toCharArray();
            final StringBuilder uri = new StringBuilder(chars.length);

            // MSITE-750: wagon dav: pseudo-protocol
            if (uriString.startsWith("dav:http")) {
                // transform dav:http to dav-http
                chars[3] = '-';
            }

            for (char c : chars) {
                if ((c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'z')
                        || (c >= 'A' && c <= 'Z')
                        || MARK.indexOf(c) != -1
                        || RESERVED.indexOf(c) != -1) {
                    uri.append(c);
                } else {
                    uri.append('%');
                    uri.append(Integer.toHexString((int) c));
                }
            }
            return uri.toString();
        }
    }
}
