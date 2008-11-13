/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.dna.graph.connectors;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.naming.Reference;
import javax.transaction.xa.XAResource;
import net.jcip.annotations.ThreadSafe;
import org.jboss.dna.graph.DnaLexicon;
import org.jboss.dna.graph.ExecutionContext;
import org.jboss.dna.graph.Location;
import org.jboss.dna.graph.cache.CachePolicy;
import org.jboss.dna.graph.properties.Name;
import org.jboss.dna.graph.properties.Path;
import org.jboss.dna.graph.properties.PathFactory;
import org.jboss.dna.graph.properties.PathNotFoundException;
import org.jboss.dna.graph.properties.Property;
import org.jboss.dna.graph.requests.CopyBranchRequest;
import org.jboss.dna.graph.requests.CreateNodeRequest;
import org.jboss.dna.graph.requests.DeleteBranchRequest;
import org.jboss.dna.graph.requests.MoveBranchRequest;
import org.jboss.dna.graph.requests.ReadAllChildrenRequest;
import org.jboss.dna.graph.requests.ReadAllPropertiesRequest;
import org.jboss.dna.graph.requests.Request;
import org.jboss.dna.graph.requests.UpdatePropertiesRequest;
import org.jboss.dna.graph.requests.processor.RequestProcessor;

/**
 * A {@link RepositorySource} for a {@link SimpleRepository simple repository}.
 * 
 * @author Randall Hauch
 */
@ThreadSafe
public class SimpleRepositorySource implements RepositorySource {

    private static final long serialVersionUID = 1L;

    /**
     * The default limit is {@value} for retrying {@link RepositoryConnection connection} calls to the underlying source.
     */
    public static final int DEFAULT_RETRY_LIMIT = 0;

    protected static final RepositorySourceCapabilities CAPABILITIES = new RepositorySourceCapabilities(true, true);

    private String repositoryName;
    private String name;
    private final AtomicInteger retryLimit = new AtomicInteger(DEFAULT_RETRY_LIMIT);
    private CachePolicy defaultCachePolicy;
    private transient RepositoryContext repositoryContext;

    public SimpleRepositorySource() {
        super();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.dna.graph.connectors.RepositorySource#initialize(org.jboss.dna.graph.connectors.RepositoryContext)
     */
    public void initialize( RepositoryContext context ) throws RepositorySourceException {
        this.repositoryContext = context;
    }

    /**
     * @return repositoryContext
     */
    public RepositoryContext getRepositoryContext() {
        return repositoryContext;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.dna.graph.connectors.RepositorySource#getName()
     */
    public String getName() {
        return name;
    }

    /**
     * @param name Sets name to the specified value.
     */
    public void setName( String name ) {
        this.name = name;
    }

    /**
     * @return repositoryName
     */
    public String getRepositoryName() {
        return repositoryName;
    }

    /**
     * @param repositoryName Sets repositoryName to the specified value.
     */
    public void setRepositoryName( String repositoryName ) {
        this.repositoryName = repositoryName;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.dna.graph.connectors.RepositorySource#getRetryLimit()
     */
    public int getRetryLimit() {
        return retryLimit.get();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.dna.graph.connectors.RepositorySource#setRetryLimit(int)
     */
    public void setRetryLimit( int limit ) {
        retryLimit.set(limit < 0 ? 0 : limit);
    }

    /**
     * @return defaultCachePolicy
     */
    public CachePolicy getDefaultCachePolicy() {
        return defaultCachePolicy;
    }

    /**
     * @param defaultCachePolicy Sets defaultCachePolicy to the specified value.
     */
    public void setDefaultCachePolicy( CachePolicy defaultCachePolicy ) {
        this.defaultCachePolicy = defaultCachePolicy;
    }

    /**
     * {@inheritDoc}
     * 
     * @see javax.naming.Referenceable#getReference()
     */
    public Reference getReference() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     * 
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /**
     * {@inheritDoc}
     * 
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals( Object obj ) {
        if (obj == this) return true;
        if (obj instanceof SimpleRepositorySource) {
            SimpleRepositorySource that = (SimpleRepositorySource)obj;
            if (!this.getName().equals(that.getName())) return false;
            if (!this.getRepositoryName().equals(that.getRepositoryName())) return false;
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.dna.graph.connectors.RepositorySource#getCapabilities()
     */
    public RepositorySourceCapabilities getCapabilities() {
        return CAPABILITIES;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.dna.graph.connectors.RepositorySource#getConnection()
     */
    public RepositoryConnection getConnection() throws RepositorySourceException {
        String reposName = this.getRepositoryName();
        if (reposName == null) throw new RepositorySourceException("Invalid repository source: missing repository name");
        SimpleRepository repository = SimpleRepository.get(reposName);
        if (repository == null) {
            throw new RepositorySourceException(this.getName(), "Unable to find repository \"" + reposName + "\"");
        }
        return new Connection(repository, this.getDefaultCachePolicy());
    }

    protected class Connection implements RepositoryConnection {

        private RepositorySourceListener listener;
        private final SimpleRepository repository;
        private final CachePolicy defaultCachePolicy;

        protected Connection( SimpleRepository repository,
                              CachePolicy defaultCachePolicy ) {
            assert repository != null;
            this.repository = repository;
            this.defaultCachePolicy = defaultCachePolicy;
        }

        /**
         * {@inheritDoc}
         * 
         * @see org.jboss.dna.graph.connectors.RepositoryConnection#close()
         */
        public void close() {
        }

        /**
         * {@inheritDoc}
         * 
         * @see org.jboss.dna.graph.connectors.RepositoryConnection#execute(org.jboss.dna.graph.ExecutionContext,
         *      org.jboss.dna.graph.requests.Request)
         */
        public void execute( ExecutionContext context,
                             Request request ) throws RepositorySourceException {
            final PathFactory pathFactory = context.getValueFactories().getPathFactory();
            final SimpleRepository repository = this.repository;
            RequestProcessor processor = new RequestProcessor(getSourceName(), context) {
                @Override
                public void process( ReadAllChildrenRequest request ) {
                    Map<Name, Property> properties = getProperties(request, request.of());
                    Path targetPath = request.of().getPath();
                    if (properties == null) return;
                    Property uuidProperty = properties.get(DnaLexicon.UUID);
                    // Iterate through all of the properties, looking for any paths that are children of the path ...
                    Map<Path, Map<Name, Property>> data = repository.getData();
                    List<Path.Segment> childSegments = new LinkedList<Path.Segment>();
                    for (Path path : data.keySet()) {
                        if (!path.isRoot() && path.getParent().equals(targetPath)) {
                            childSegments.add(path.getLastSegment());
                        }
                    }
                    // This does not store children order, so sort ...
                    Collections.sort(childSegments);
                    // Now get the children ...
                    for (Path.Segment childSegment : childSegments) {
                        Path childPath = pathFactory.create(targetPath, childSegment);
                        Map<Name, Property> childProperties = repository.getData().get(childPath);
                        Property childUuidProperty = childProperties.get(DnaLexicon.UUID);
                        request.addChild(childPath, childUuidProperty);
                    }
                    request.setActualLocationOfNode(request.of().with(uuidProperty));
                }

                @Override
                public void process( ReadAllPropertiesRequest request ) {
                    Map<Name, Property> properties = getProperties(request, request.at());
                    if (properties == null) return;
                    Property uuidProperty = properties.get(DnaLexicon.UUID);
                    for (Property property : properties.values()) {
                        if (property != uuidProperty) request.addProperty(property);
                    }
                    request.setActualLocationOfNode(request.at().with(uuidProperty));
                }

                @Override
                public void process( CopyBranchRequest request ) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void process( CreateNodeRequest request ) {
                    Path targetPath = request.at().getPath();
                    ExecutionContext context = getExecutionContext();
                    repository.create(context, targetPath.getString(context.getNamespaceRegistry()));
                    Map<Name, Property> properties = repository.getData().get(targetPath);
                    assert properties != null;
                    // Set the UUID if the request has one ...
                    Property uuidProperty = request.at().getIdProperty(DnaLexicon.UUID);
                    if (uuidProperty != null) {
                        properties.put(uuidProperty.getName(), uuidProperty);
                        request.setActualLocationOfNode(request.at());
                    } else {
                        uuidProperty = properties.get(DnaLexicon.UUID);
                        request.setActualLocationOfNode(request.at().with(uuidProperty));
                    }
                    for (Property property : request.properties()) {
                        if (property != null) properties.put(property.getName(), property);
                    }
                }

                @Override
                public void process( DeleteBranchRequest request ) {
                    // Iterate through all of the dataq, looking for any paths that are children of the path ...
                    Path targetPath = request.at().getPath();
                    Map<Path, Map<Name, Property>> data = repository.getData();
                    Map<Name, Property> properties = repository.getData().get(targetPath);
                    Property uuidProperty = properties.get(DnaLexicon.UUID);
                    for (Path path : data.keySet()) {
                        if (!path.isRoot() && path.isAtOrBelow(targetPath)) {
                            data.remove(path);
                        }
                    }
                    request.setActualLocationOfNode(request.at().with(uuidProperty));
                }

                @Override
                public void process( MoveBranchRequest request ) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void process( UpdatePropertiesRequest request ) {
                    Map<Name, Property> properties = getProperties(request, request.on());
                    if (properties == null) return;
                    Property uuidProperty = properties.get(DnaLexicon.UUID);
                    for (Property property : request.properties()) {
                        if (property != uuidProperty) properties.put(property.getName(), property);
                    }
                    request.setActualLocationOfNode(request.on().with(uuidProperty));
                }

                protected Map<Name, Property> getProperties( Request request,
                                                             Location location ) {
                    Path targetPath = location.getPath();
                    if (targetPath == null) throw new UnsupportedOperationException();
                    Map<Name, Property> properties = repository.getData().get(targetPath);
                    if (properties == null) {
                        Path ancestor = targetPath.getParent();
                        while (ancestor != null) {
                            if (repository.getData().get(targetPath) != null) break;
                            ancestor = ancestor.getParent();
                        }
                        if (ancestor == null) ancestor = getExecutionContext().getValueFactories().getPathFactory().createRootPath();
                        request.setError(new PathNotFoundException(location, ancestor));
                        return null;
                    }
                    return properties;
                }
            };
            processor.process(request);
        }

        /**
         * {@inheritDoc}
         * 
         * @see org.jboss.dna.graph.connectors.RepositoryConnection#getDefaultCachePolicy()
         */
        public CachePolicy getDefaultCachePolicy() {
            return defaultCachePolicy;
        }

        /**
         * {@inheritDoc}
         * 
         * @see org.jboss.dna.graph.connectors.RepositoryConnection#getSourceName()
         */
        public String getSourceName() {
            return SimpleRepositorySource.this.getName();
        }

        /**
         * {@inheritDoc}
         * 
         * @see org.jboss.dna.graph.connectors.RepositoryConnection#getXAResource()
         */
        public XAResource getXAResource() {
            return null;
        }

        /**
         * {@inheritDoc}
         * 
         * @see org.jboss.dna.graph.connectors.RepositoryConnection#ping(long, java.util.concurrent.TimeUnit)
         */
        public boolean ping( long time,
                             TimeUnit unit ) {
            return true;
        }

        /**
         * {@inheritDoc}
         * 
         * @see org.jboss.dna.graph.connectors.RepositoryConnection#setListener(org.jboss.dna.graph.connectors.RepositorySourceListener)
         */
        public void setListener( RepositorySourceListener listener ) {
            this.listener = listener;
        }

        /**
         * @return listener
         */
        public RepositorySourceListener getListener() {
            return listener;
        }
    }
}
