package de.fuberlin.wiwiss.pubby;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.shared.PrefixMapping;
import com.hp.hpl.jena.sparql.engine.http.QueryEngineHTTP;
import com.hp.hpl.jena.vocabulary.RDF;
import java.util.logging.Logger;

/**
 * A convenient interface to an RDF description of a resource. Provides access
 * to its label, a textual comment, detailed representations of its properties,
 * and so on.
 *
 * @author Richard Cyganiak (richard@cyganiak.de)
 * @author KAi Eckert (kai@informatik.uni-mannheim.de)
 * @version $Id$
 */
public class ResourceDescription {

    private final MappedResource mappedResource;
    private final Model model;
    private final Resource resource;
    private final Configuration config;
    private List properties = null;

    private Logger log = Logger.getLogger(getClass().getName());

    public ResourceDescription(MappedResource mappedResource, Model model,
            Configuration config) {
        this.mappedResource = mappedResource;
        this.model = model;
        this.resource = model.getResource(mappedResource.getWebURI());
        this.config = config;
    }

    public ResourceDescription(Resource resource, Model model, Configuration config) {
        this.mappedResource = null;
        this.model = model;
        this.resource = resource;
        this.config = config;
    }

    public String getURI() {
        if (mappedResource == null) {
            return null;
        }
        return mappedResource.getWebURI();
    }

    public String getLabel() {
        Collection candidates = getValuesFromMultipleProperties(config.getLabelProperties());
        String label = getBestLanguageMatch(
                candidates, config.getDefaultLanguage());
        if (label == null) {
            return resource.getLocalName();
        }
        return label;
    }

    public String getComment() {
        Collection candidates = getValuesFromMultipleProperties(config.getCommentProperties());
        return getBestLanguageMatch(candidates, config.getDefaultLanguage());
    }

    public String getImageURL() {
        Collection candidates = getValuesFromMultipleProperties(config.getImageProperties());
        Iterator it = candidates.iterator();
        while (it.hasNext()) {
            RDFNode candidate = (RDFNode) it.next();
            if (candidate.isURIResource()) {
                return ((Resource) candidate.as(Resource.class)).getURI();
            }
        }
        return null;
    }

    public String getThumbnailURL() {
        String image = getImageURL();
        if (image == null) {
            return null;
        }
        try {
            return config.getWebApplicationBaseURI() + "image?url=" + URLEncoder.encode(image, "utf-8") + "&width=200&height=300";
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("This can not happen! ", e);
        }
    }

    public List getProperties() {
        if (properties == null) {
            properties = buildProperties();
        }
        return properties;
    }

    private List buildProperties() {
        log.fine("build props of : " + resource.toString());
        Map propertyBuilders = new HashMap();
        StmtIterator it = resource.listProperties();
        while (it.hasNext()) {
            Statement stmt = it.nextStatement();
            Property predicate = stmt.getPredicate();
            String key = "=>" + predicate;
            if (!propertyBuilders.containsKey(key)) {
                propertyBuilders.put(key, new PropertyBuilder(predicate, false, config.getVocabularyCache()));
            }
            ((PropertyBuilder) propertyBuilders.get(key)).addValue(stmt.getObject());
            log.fine("found statement => : " + stmt.getSubject());
        }
        it = model.listStatements(null, null, resource);
        //backlinks           
        while (it.hasNext()) {
            Statement stmt = it.nextStatement();
            Property predicate = stmt.getPredicate();
            String key = "<=" + predicate;
            //exclude backlinks if they come from a reification
            if (predicate.getLocalName().equals(RDF.subject.getLocalName()) || predicate.getLocalName().equals(RDF.object.getLocalName())) {
                continue;
            }
            if (!propertyBuilders.containsKey(key)) {
                propertyBuilders.put(key, new PropertyBuilder(predicate, true, config.getVocabularyCache()));
            }
            ((PropertyBuilder) propertyBuilders.get(key)).addValue(stmt.getSubject());
            log.fine("found statement <= : " + stmt.getSubject());
        }

        List<ResourceProperty> results = new ArrayList();
        Iterator it2 = propertyBuilders.values().iterator();
        while (it2.hasNext()) {
            PropertyBuilder propertyBuilder = (PropertyBuilder) it2.next();
            results.add(propertyBuilder.toProperty());
        }
        Collections.sort(results);
        return results;
    }

    private PrefixMapping getPrefixes() {
        return model;
    }

    private Collection getValuesFromMultipleProperties(Collection properties) {
        Collection results = new ArrayList();
        Iterator it = properties.iterator();
        while (it.hasNext()) {
            com.hp.hpl.jena.rdf.model.Property property = (com.hp.hpl.jena.rdf.model.Property) it.next();
            StmtIterator labelIt = resource.listProperties(property);
            while (labelIt.hasNext()) {
                RDFNode label = labelIt.nextStatement().getObject();
                results.add(label);
            }
        }
        return results;
    }

    private String getBestLanguageMatch(Collection nodes, String lang) {
        Iterator it = nodes.iterator();
        String aLiteral = null;
        while (it.hasNext()) {
            RDFNode candidate = (RDFNode) it.next();
            if (!candidate.isLiteral()) {
                continue;
            }
            Literal literal = (Literal) candidate.as(Literal.class);
            if (lang == null
                    || lang.equals(literal.getLanguage())) {
                return literal.getString();
            }
            aLiteral = literal.getString();
        }
        return aLiteral;
    }

    /**
     * Get the origin(s) of a triple. If a triple is contained in more than one
     * graph, return all of them. If a triple is contained in the graphs
     * only refereing to different versions, only return the newest version.
     *
     * @param property
     * @param value
     * @return
     */
    public List<String> getOrigin(ResourceProperty property, Value value) {
        List<String> origins = new ArrayList<String>();

        //the origin should be shown
        if (mappedResource.getDataset().isShowOrigin()) {
            //query the triple and return all origins
            if (value.getNode().isLiteral()) {
                origins = getProv(mappedResource.getDatasetURI(), value.getNode().getLiteralLexicalForm(), property.isInverse, mappedResource.getDataset().getEndpoint());
            } else {
                String uri = "";
                if (value.getNode().isURI()) {
                    //get the object URI and set the correct URI parts based on the config.
                    //this avoid problems with different triplestore/application URIs like localhost vs. data.dm2e.eu
                    uri = value.getNode().getURI();
                    if (uri.contains(mappedResource.getServerConfig().getWebApplicationBaseURI())) {
                        uri = uri.replace(mappedResource.getServerConfig().getWebApplicationBaseURI(), "");
                        uri = uri.replace(mappedResource.getDatasetConfig().getWebDataPrefix(), "");
                        uri = mappedResource.getDatasetConfig().getDatasetBase() + "" + uri;
                    }
                    log.fine("URI " + uri);

                }
                origins = getProv(mappedResource.getDatasetURI(), uri, property.isInverse, mappedResource.getDataset().getEndpoint());
            }
        }
        return origins;
    }

    /**
     * Function to query the origin.
     * 
     * @param subject
     * @param object
     * @param inverse
     * @param endpoint
     * @return 
     */
    private List<String> getProv(String subject, String object, boolean inverse, String endpoint) {

        //if the property is inverse, switch object and subject
        if (inverse) {
            String tmp = subject;
            subject = object;
            object = tmp;
        }

        List<String> uris = new ArrayList<String>();

        String query = "";
        if (object.contains("http://")) {
            query = "select ?g ?date WHERE {      "
                    + "graph ?g {<" + subject + "> ?p <" + object + ">."
                    + "   ?g <http://purl.org/dc/elements/1.1/date> ?date .  "
                    + "}               } ORDER BY DESC(?date) ";
        } else {
            query = "select ?g ?date WHERE {      "
                    + "graph ?g {<" + subject + "> ?p ?o."
                    + "   ?g <http://purl.org/dc/elements/1.1/date> ?date .  "
                    + "filter (STR(?o)=\"" + object + "\")"
                    + "}               } ORDER BY DESC(?date) ";
        }

        QueryEngineHTTP queryEngine = new QueryEngineHTTP(endpoint, query);

        ResultSet rs = queryEngine.execSelect();
        while (rs.hasNext()) {
            boolean alreadyInserted = false;
            QuerySolution next = rs.next();
            String graph = next.get("g").toString();
            String graphURI = graph.substring(0, graph.lastIndexOf("/"));
            String uri = graph;

            for (String detecteGraphs : uris) {
                if (detecteGraphs.contains(graphURI)) {
                    alreadyInserted = true;
                }
            }
            //the graphs returned first are the newer ones
            if (!alreadyInserted) {
                uris.add(uri);
            }
        }
        return uris;
    }

    public boolean displayUnicorn() {
        return mappedResource.getDataset().isUnicorn();
    }

    public class ResourceProperty implements Comparable {

        private final Property predicate;
        private final URIPrefixer predicatePrefixer;
        private final boolean isInverse;
        private final List values;
        private final int blankNodeCount;
        private VocabularyCache vocabularyCache;

        public ResourceProperty(Property predicate, boolean isInverse, List values,
                int blankNodeCount, VocabularyCache vocabularyCache) {
            this.predicate = predicate;
            this.predicatePrefixer = new URIPrefixer(predicate, getPrefixes());
            this.isInverse = isInverse;
            this.values = values;
            this.blankNodeCount = blankNodeCount;
            this.vocabularyCache = vocabularyCache;
        }

        public boolean isInverse() {
            return isInverse;
        }

        public String getURI() {
            return predicate.getURI();
        }

        public boolean hasPrefix() {
            return predicatePrefixer.hasPrefix();
        }

        public String getPrefix() {
            return predicatePrefixer.getPrefix();
        }

        public String getLocalName() {
            return predicatePrefixer.getLocalName();
        }

        public String getLabel() {
            return vocabularyCache.getLabel(predicate.getURI());
        }

        public String getDescription() {
            return vocabularyCache.getDescription(predicate.getURI());
        }

        public List getValues() {
            return values;
        }

        public int getBlankNodeCount() {
            return blankNodeCount;
        }

        public String getPathPageURL() {
            if (mappedResource == null) {
                return null;
            }
            return isInverse
                    ? mappedResource.getInversePathPageURL(predicate)
                    : mappedResource.getPathPageURL(predicate);
        }

        public int compareTo(Object other) {
            if (!(other instanceof ResourceProperty)) {
                return 0;
            }
            ResourceProperty otherProperty = (ResourceProperty) other;
            String propertyLocalName = getLocalName();
            String otherLocalName = otherProperty.getLocalName();
            if (propertyLocalName.compareTo(otherLocalName) != 0) {
                return propertyLocalName.compareTo(otherLocalName);
            }
            if (this.isInverse() != otherProperty.isInverse()) {
                return (this.isInverse()) ? -1 : 1;
            }
            return 0;
        }
    }

    private class PropertyBuilder {

        private final Property predicate;
        private final boolean isInverse;
        private final List values = new ArrayList();
        private int blankNodeCount = 0;
        private VocabularyCache vocabularyCache;

        PropertyBuilder(Property predicate, boolean isInverse, VocabularyCache vocabularyCache) {
            this.predicate = predicate;
            this.isInverse = isInverse;
            this.vocabularyCache = vocabularyCache;
        }

        void addValue(RDFNode valueNode) {
            if (valueNode.isAnon()) {
                blankNodeCount++;
                return;
            }
            values.add(new Value(valueNode, predicate, vocabularyCache));
        }

        ResourceProperty toProperty() {
            Collections.sort(values);
            return new ResourceProperty(predicate, isInverse, values, blankNodeCount, vocabularyCache);
        }
    }

    public class Value implements Comparable {

        private final RDFNode node;
        private URIPrefixer prefixer;
        private Property predicate;
        private VocabularyCache vocabularyCache;

        public Value(RDFNode valueNode, Property predicate, VocabularyCache vocabularyCache) {
            this.node = valueNode;
            this.predicate = predicate;
            this.vocabularyCache = vocabularyCache;
            if (valueNode.isURIResource()) {
                prefixer = new URIPrefixer((Resource) valueNode.as(Resource.class), getPrefixes());
            }
        }

        public Node getNode() {
            return node.asNode();
        }

        public boolean hasPrefix() {
            return prefixer != null && prefixer.hasPrefix();
        }

        public String getPrefix() {
            if (prefixer == null) {
                return null;
            }
            return prefixer.getPrefix();
        }

        public String getLocalName() {
            if (prefixer == null) {
                return null;
            }
            return prefixer.getLocalName();
        }

        public String getLabel() {
            return vocabularyCache.getLabel(node.asNode().getURI());
        }

        public String getDescription() {
            return vocabularyCache.getDescription(node.asNode().getURI());
        }

        public String getDatatypeLabel() {
            if (!node.isLiteral()) {
                return null;
            }
            String uri = ((Literal) node.as(Literal.class)).getDatatypeURI();
            if (uri == null) {
                return null;
            }
            URIPrefixer datatypePrefixer = new URIPrefixer(uri, getPrefixes());
            return datatypePrefixer.toTurtle();
        }

        public boolean isType() {
            return predicate.equals(RDF.type);
        }

        /**
         * Checks whether an link (determined by the getExternalLinkProperty),
         * e.g. sameAs, is available for the resource. If this is the case, a
         * "plus" buttonrefering to the overview page of the external resource
         * will be displayed. This page provides information about other
         * resources that are linked to the same (external) resource.
         *
         * @return
         */
        public String linksToExternalSource() {
            //TODO: can we also do it with "interal" links?
            if (predicate.getURI().equals(mappedResource.getDatasetConfig().getExternalLinkProperty()) && !this.getLabel().contains(mappedResource.getServerConfig().getWebApplicationBaseURI())) {
                String uri = this.getNode().getURI();
                String resultingURI = mappedResource.getServerConfig().getWebApplicationBaseURI()
                        + mappedResource.getDatasetConfig().getWebResourcePrefix() + "" + mappedResource.getDatasetConfig().getExternalLinkPrefix() + "" + uri.replace("http://", "");
                return resultingURI;
            } else {
                return "";
            }
        }

        public int compareTo(Object other) {
            if (!(other instanceof Value)) {
                return 0;
            }
            Value otherValue = (Value) other;
            if (getNode().isURI() && otherValue.getNode().isURI()) {
                return getNode().getURI().compareTo(otherValue.getNode().getURI());
            }
            if (getNode().isURI()) {
                return 1;
            }
            if (otherValue.getNode().isURI()) {
                return -1;
            }
            if (getNode().isBlank() && otherValue.getNode().isBlank()) {
                return getNode().getBlankNodeLabel().compareTo(otherValue.getNode().getBlankNodeLabel());
            }
            if (getNode().isBlank()) {
                return 1;
            }
            if (otherValue.getNode().isBlank()) {
                return -1;
            }
            // TODO Typed literals, language literals
            return getNode().getLiteralLexicalForm().compareTo(otherValue.getNode().getLiteralLexicalForm());
        }
    }
}
