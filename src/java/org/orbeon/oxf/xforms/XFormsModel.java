/**
 *  Copyright (C) 2004 - 2008 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.QName;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.ProcessorImpl;
import org.orbeon.oxf.util.ConnectionResult;
import org.orbeon.oxf.util.NetUtils;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.XFormsControlFactory;
import org.orbeon.oxf.xforms.event.*;
import org.orbeon.oxf.xforms.event.events.*;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xml.TransformerUtils;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.saxon.dom4j.NodeWrapper;
import org.orbeon.saxon.om.DocumentInfo;
import org.orbeon.saxon.om.NodeInfo;

import java.net.URI;
import java.net.URL;
import java.util.*;

/**
 * Represents an XForms model.
 */
public class XFormsModel implements XFormsEventTarget, XFormsEventHandlerContainer, Cloneable {

    private Document modelDocument;

    // Model attributes
    private String modelId;
    private String modelEffectiveId;

    // Instances
    private List instanceIds;
    private List instances;
    private Map instancesMap;

    // Map<String, XFormsModelSubmission> of submission ids to submission objects
    private Map submissions;

    // Binds
    private XFormsModelBinds binds;

    // Schema validation
    private XFormsModelSchemaValidator schemaValidator;

    // Container
    private XFormsContainer container;

    // Containing document
    private XFormsContainingDocument containingDocument;

    // For legacy XForms engine
    private InstanceConstructListener instanceConstructListener;


    public XFormsModel(String prefixedId, Document modelDocument) {
        this.modelDocument = modelDocument;

        // Basic check trying to make sure this is an XForms model
        // TODO: should rather use schema here or when obtaining document passed to this constructor
        final Element modelElement = modelDocument.getRootElement();
        String rootNamespaceURI = modelElement.getNamespaceURI();
        if (!rootNamespaceURI.equals(XFormsConstants.XFORMS_NAMESPACE_URI))
            throw new ValidationException("Root element of XForms model must be in namespace '"
                    + XFormsConstants.XFORMS_NAMESPACE_URI + "'. Found instead: '" + rootNamespaceURI + "'",
                    (LocationData) modelElement.getData());

        // Get model id (may be null) (really? how? legacy mode?)
        modelId = modelElement.attributeValue("id");
        modelEffectiveId = prefixedId;

        // Extract list of instances ids
        {
            List instanceContainers = modelElement.elements(new QName("instance", XFormsConstants.XFORMS_NAMESPACE));
            instanceIds = new ArrayList(instanceContainers.size());
            if (instanceContainers.size() > 0) {
                for (Iterator i = instanceContainers.iterator(); i.hasNext();) {
                    final Element instanceContainer = (Element) i.next();
                    final String instanceId = XFormsInstance.getInstanceId(instanceContainer);
                    instanceIds.add(instanceId);
                }
            }
        }
    }

    public XFormsModel(Document modelDocument) {// legacy
        this(modelDocument.getRootElement().attributeValue("id"), modelDocument);
    }

    public void setContainer(XFormsContainer container) {

        this.container = container;
        this.containingDocument = container.getContainingDocument();

        final Element modelElement = modelDocument.getRootElement();

        // Get <xforms:submission> elements (may be missing)
        {
            for (Iterator i = modelElement.elements(new QName("submission", XFormsConstants.XFORMS_NAMESPACE)).iterator(); i.hasNext();) {
                final Element submissionElement = (Element) i.next();
                String submissionId = submissionElement.attributeValue("id");
                if (submissionId == null)// Can this happen? Maybe with the legacy engine?
                    submissionId = "";

                if (this.submissions == null)
                    this.submissions = new HashMap();
                this.submissions.put(submissionId, new XFormsModelSubmission(this.containingDocument, submissionId, submissionElement, this));
            }
        }

        // Create binds object
        binds = new XFormsModelBinds(this);
    }

    public XFormsContainer getContainer() {
        return container;
    }

    public XFormsContainingDocument getContainingDocument() {
        return containingDocument;
    }

    public Document getModelDocument() {
        return modelDocument;
    }

    /**
     * Get object with the id specified.
     */
    public Object getObjectByEffectiveId(String effectiveId) {

        // Check model itself
        if (effectiveId.equals(modelEffectiveId))
            return this;

        // Search instances
        if (instancesMap != null) {
            final XFormsInstance instance = (XFormsInstance) instancesMap.get(effectiveId);
            if (instance != null)
                return instance;
        }

        // Search submissions
        if (submissions != null) {
            final XFormsModelSubmission resultSubmission = (XFormsModelSubmission) submissions.get(effectiveId);
            if (resultSubmission != null)
                return resultSubmission;
        }

        return null;
    }

    /**
     * Resolve an object. This optionally depends on a source, and involves resolving whether the source is within a
     * repeat or a component.
     *
     * @param effectiveSourceId  effective id of the source, or null
     * @param targetId           id of the target
     * @return                   object, or null if not found
     */
    public Object resolveObjectById(String effectiveSourceId, String targetId) {

        if (targetId.indexOf(XFormsConstants.COMPONENT_SEPARATOR) != -1 || targetId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1) != -1)
            throw new OXFException("Target id must be static id: " + targetId);

        if (effectiveSourceId != null) {
            final String prefix = XFormsUtils.getEffectiveIdPrefix(effectiveSourceId);
            if (!prefix.equals("")) {
                // Source is in a component so we can only reach items within this same component instance
                final String effectiveTargetId = prefix + targetId;
                return getObjectByEffectiveId(effectiveTargetId);
            }
        }

        return getObjectByEffectiveId(targetId);
    }

    /**
     * Return the default instance for this model, i.e. the first instance. Return null if there is
     * no instance in this model.
     *
     * @return  XFormsInstance or null
     */
    public XFormsInstance getDefaultInstance() {
        return (XFormsInstance) ((instances != null && instances.size() > 0) ? instances.get(0) : null);
    }

    /**
     * Return the id of the default instance for this model. Return null if there is no isntance in this model.
     *
     * @return  instance id or null
     */
    public String getDefaultInstanceId() {
        return (instanceIds != null && instanceIds.size() > 0) ? (String) instanceIds.get(0) : null;
    }

    /**
     * Return all XFormsInstance objects for this model, in the order they appear in the model.
     */
    public List getInstances() {
        return instances;
    }

    /**
     * Return the number of instances in this model.
     */
    public int getInstanceCount() {
        return instanceIds.size();
    }

    /**
     * Return the XFormsInstance with given id, null if not found.
     */
    public XFormsInstance getInstance(String instanceId) {
        return (instancesMap == null) ? null : (XFormsInstance) (instancesMap.get(instanceId));
    }

    /**
     * Return the XFormsInstance object containing the given node.
     */
    public XFormsInstance getInstanceForNode(NodeInfo nodeInfo) {

        final DocumentInfo documentInfo = nodeInfo.getDocumentRoot();

        if (instances != null) {
            for (Iterator i = instances.iterator(); i.hasNext();) {
                final XFormsInstance currentInstance = (XFormsInstance) i.next();
                if (currentInstance.getDocumentInfo().isSameNodeInfo(documentInfo))
                    return currentInstance;
            }
        }

        return null;
    }

    /**
     * Set an instance document for this model. There may be multiple instance documents. Each instance document may
     * have an associated id that identifies it.
     */
    public XFormsInstance setInstanceDocument(Object instanceDocument, String modelEffectiveId, String instanceId, String instanceSourceURI, String username, String password, boolean shared, long timeToLive, String validation) {
        // Initialize containers if needed
        if (instances == null) {
            instances = Arrays.asList(new XFormsInstance[instanceIds.size()]);
            instancesMap = new HashMap(instanceIds.size());
        }
        // Prepare and set instance
        final int instancePosition = instanceIds.indexOf(instanceId);
        final XFormsInstance newInstance;
        {
            if (instanceDocument instanceof Document)
                newInstance = new XFormsInstance(modelEffectiveId, instanceId, (Document) instanceDocument, instanceSourceURI, username, password, shared, timeToLive, validation);
            else if (instanceDocument instanceof DocumentInfo)
                newInstance = new SharedXFormsInstance(modelEffectiveId, instanceId, (DocumentInfo) instanceDocument, instanceSourceURI, username, password, shared, timeToLive, validation);
            else
                throw new OXFException("Invalid type for instance document: " + instanceDocument.getClass().getName());
        }
        instances.set(instancePosition, newInstance);

        // Create mapping instance id -> instance
        if (instanceId != null)
            instancesMap.put(instanceId, newInstance);

        return newInstance;
    }

    /**
     * Set an instance. The id of the instance must exist in the model.
     *
     * @param instance          XFormsInstance to set
     * @param replaced          whether this is an instance replacement (as result of a submission)
     */
    public void setInstance(XFormsInstance instance, boolean replaced) {

        // Mark the instance as replaced if needed
        instance.setReplaced(replaced);

        // Initialize containers if needed
        if (instances == null) {
            instances = Arrays.asList(new XFormsInstance[instanceIds.size()]);
            instancesMap = new HashMap(instanceIds.size());
        }
        // Prepare and set instance
        final String instanceId = instance.getId();// use static id as instanceIds contains static ids
        final int instancePosition = instanceIds.indexOf(instanceId);

        instances.set(instancePosition, instance);

        // Create mapping instance id -> instance
        if (instanceId != null)
            instancesMap.put(instanceId, instance);
    }

    public String getId() {
        return modelId;
    }

    public String getEffectiveId() {
        return modelEffectiveId;
    }

    public LocationData getLocationData() {
        return (LocationData) modelDocument.getRootElement().getData();
    }

    public XFormsModelBinds getBinds() {
        return binds;
    }

    public XFormsModelSchemaValidator getSchemaValidator() {
        return schemaValidator;
    }

    private void loadSchemasIfNeeded(PipelineContext pipelineContext) {
        final Element modelElement = modelDocument.getRootElement();
        if (schemaValidator == null) {
            if (!XFormsProperties.isSkipSchemaValidation(containingDocument)) {
                schemaValidator = new XFormsModelSchemaValidator(modelElement);
                schemaValidator.loadSchemas(pipelineContext);
            }
        }
    }

    private void applySchemasIfNeeded(Map invalidInstances) {
        // Don't do anything if there is no schema
        if (schemaValidator != null) {
            // Apply schemas to all instances
            if (getInstances() != null) {
                for (Iterator i = getInstances().iterator(); i.hasNext();) {
                    final XFormsInstance currentInstance = (XFormsInstance) i.next();
                    // Currently we don't support validating read-only instances
                    if (!currentInstance.isReadOnly()) {
                        if (!schemaValidator.validateInstance(currentInstance)) {
                            // Remember that instance is invalid
                            invalidInstances.put(currentInstance.getEffectiveId(), "");
                        }
                    }
                }
            }
        }
    }

    public String[] getSchemaURIs() {
        if (schemaValidator != null) {
            return schemaValidator.getSchemaURIs();
        } else {
            return null;
        }
    }

    /**
     * Initialize the state of the model when the model object was just recreated.
     */
    public void initializeState(PipelineContext pipelineContext ) {
        // Ensure schemas are loaded
        loadSchemasIfNeeded(pipelineContext);

        // Refresh binds
        doRebuild(pipelineContext);
        binds.applyComputedExpressionBinds(pipelineContext);
        doRevalidate(pipelineContext);

        synchronizeInstanceDataEventState();
    }

    public void performDefaultAction(final PipelineContext pipelineContext, XFormsEvent event) {
        final String eventName = event.getEventName();
        if (XFormsEvents.XFORMS_MODEL_CONSTRUCT.equals(eventName)) {
            // 4.2.1 The xforms-model-construct Event
            // Bubbles: Yes / Cancelable: No / Context Info: None

            final Element modelElement = modelDocument.getRootElement();

            // 1. All XML Schemas loaded (throws xforms-link-exception)

            loadSchemasIfNeeded(pipelineContext);
            // TODO: throw exception event

            // 2. Create XPath data model from instance (inline or external) (throws xforms-link-exception)
            //    Instance may not be specified.

//            if (instances == null) {
            if (instances == null) {
                instances = Arrays.asList(new XFormsInstance[instanceIds.size()]);
                instancesMap = new HashMap(instanceIds.size());
            }
            {
                // Build initial instance documents
                final List instanceContainers = modelElement.elements(new QName("instance", XFormsConstants.XFORMS_NAMESPACE));
                final XFormsStaticState staticState = containingDocument.getStaticState();
                final Map staticStateInstancesMap = (staticState != null && staticState.isInitialized()) ? staticState.getSharedInstancesMap() : null;
                if (instanceContainers.size() > 0) {
                    // Iterate through all instances
                    int instancePosition = 0;
                    for (Iterator i = instanceContainers.iterator(); i.hasNext(); instancePosition++) {

                        final Element instanceContainerElement = (Element) i.next();
                        final LocationData locationData = (LocationData) instanceContainerElement.getData();
                        final String instanceId = XFormsInstance.getInstanceId(instanceContainerElement);

                        // Handle read-only hints
                        final boolean isReadonlyHint = XFormsInstance.isReadonlyHint(instanceContainerElement);
                        final boolean isApplicationSharedHint = XFormsInstance.isApplicationSharedHint(instanceContainerElement);
                        final long xxformsTimeToLive = XFormsInstance.getTimeToLive(instanceContainerElement);

                        // Skip processing in case somebody has already set this particular instance
                        if (instances.get(instancePosition) != null)
                            continue;

                        // Get instance from static state if possible
                        if (staticStateInstancesMap != null) {
                            final XFormsInstance staticStateInstance = (XFormsInstance) staticStateInstancesMap.get(instanceId);
                            if (staticStateInstance != null) {
                                // The instance is already available in the static state

                                if (staticStateInstance.getDocumentInfo() == null) {
                                    // Instance is not initialized yet

                                    // This means that the instance was application shared
                                    if (!staticStateInstance.isApplicationShared())
                                        throw new ValidationException("Non-initialized instance has to be application shared for id: " + staticStateInstance.getEffectiveId(),
                                                (LocationData) instanceContainerElement.getData());

                                    if (XFormsServer.logger.isDebugEnabled())
                                        containingDocument.logDebug("model", "using instance from application shared instance cache (instance from static state was not initialized)",
                                                new String[] { "id", staticStateInstance.getEffectiveId() });

                                    final SharedXFormsInstance sharedInstance
                                            = XFormsServerSharedInstancesCache.instance().find(pipelineContext, containingDocument, staticStateInstance.getEffectiveId(), staticStateInstance.getEffectiveModelId(), staticStateInstance.getSourceURI(), staticStateInstance.getTimeToLive(), staticStateInstance.getValidation());
                                    setInstance(sharedInstance, false);

                                } else {
                                    // Instance is initialized, just use it

                                    if (XFormsServer.logger.isDebugEnabled())
                                        containingDocument.logDebug("model", "using initialized instance from static state",
                                                new String[] { "id", staticStateInstance.getEffectiveId() });

                                    setInstance(staticStateInstance, false);
                                }

                                continue;
                            }
                        }

                        // Did not get the instance from static state
                        final Object instanceDocument;// Document or DocumentInfo
                        final String instanceSourceURI;
                        final String xxformsUsername;
                        final String xxformsPassword;
                        final String xxformsValidation = instanceContainerElement.attributeValue(XFormsConstants.XXFORMS_VALIDATION_QNAME);

                        final long startTime = XFormsServer.logger.isDebugEnabled() ? System.currentTimeMillis() : 0;
                        final String instanceResource;
                        {
                            final String srcAttribute = instanceContainerElement.attributeValue("src");
                            final String resourceAttribute = instanceContainerElement.attributeValue("resource");
                            if (srcAttribute != null)
                                instanceResource = XFormsUtils.encodeHRRI(srcAttribute, true);
                            else if (resourceAttribute != null)
                                instanceResource = XFormsUtils.encodeHRRI(resourceAttribute, true);
                            else
                                instanceResource = null;
                        }
                        if (instanceResource == null) {
                            // Inline instance
                            final String xxformsExcludeResultPrefixes = instanceContainerElement.attributeValue(XFormsConstants.XXFORMS_EXCLUDE_RESULT_PREFIXES);
                            final List children = instanceContainerElement.elements();
                            if (children == null || children.size() != 1) {
                                final Throwable throwable = new ValidationException("xforms:instance element must contain exactly one child element",
                                        new ExtendedLocationData(locationData, "processing inline XForms instance", instanceContainerElement));
                                containingDocument.dispatchEvent(pipelineContext, new XFormsLinkExceptionEvent(XFormsModel.this, null, instanceContainerElement, throwable));
                                break;
                            }
                            {
                                final Document tempDocument;
                                // TODO: Implement as per XSLT 2.0. For now, we just support #all.
                                // TODO: Must implement namespace fixup, the code below can break serialization
                                if ("#all".equals(xxformsExcludeResultPrefixes)) {
                                    tempDocument = Dom4jUtils.createDocumentCopyElement((Element) children.get(0));
                                } else if (xxformsExcludeResultPrefixes != null) {
                                    final StringTokenizer st = new StringTokenizer(xxformsExcludeResultPrefixes);
                                    final Map prefixesToExclude = new HashMap();
                                    while (st.hasMoreTokens()) {
                                        prefixesToExclude.put(st.nextToken(), "");
                                    }
                                    tempDocument = Dom4jUtils.createDocumentCopyParentNamespaces((Element) children.get(0), prefixesToExclude);
                                } else {
                                    tempDocument = Dom4jUtils.createDocumentCopyParentNamespaces((Element) children.get(0));
                                }

                                if (!isReadonlyHint) {
                                    instanceDocument = tempDocument;
                                } else {
                                    instanceDocument = TransformerUtils.dom4jToTinyTree(tempDocument);
                                }
                            }
                            instanceSourceURI = null;
                            xxformsUsername = null;
                            xxformsPassword = null;
                        } else if (!instanceResource.trim().equals("")) {

                            // External instance
                            final ExternalContext externalContext = (ExternalContext) pipelineContext.getAttribute(PipelineContext.EXTERNAL_CONTEXT);

                            // NOTE: Optimizing with include() for servlets doesn't allow detecting errors caused by
                            // the included resource, so we don't allow this for now. Furthermore, we are forced to
                            // "optimize" for portlet access.

//                            final boolean optimize = !NetUtils.urlHasProtocol(srcAttribute)
//                               && (externalContext.getRequest().getContainerType().equals("portlet")
//                                    || (externalContext.getRequest().getContainerType().equals("servlet")
//                                        && XFormsUtils.isOptimizeLocalInstanceLoads()));

                            final boolean optimizeForPortlets = !NetUtils.urlHasProtocol(instanceResource)
                                                        && externalContext.getRequest().getContainerType().equals("portlet");

                            final ConnectionResult connectionResult;
                            if (optimizeForPortlets) {
                                // Use optimized local mode

                                final URI resolvedURI = XFormsUtils.resolveXMLBase(instanceContainerElement, instanceResource);

                                if (XFormsServer.logger.isDebugEnabled())
                                    containingDocument.logDebug("model", "getting document from optimized URI",
                                                new String[] { "URI", resolvedURI.toString() });

                                connectionResult = XFormsSubmissionUtils.openOptimizedConnection(pipelineContext, externalContext,
                                        null, null, "get", resolvedURI.toString(), null, false, null, null);

                                instanceSourceURI = resolvedURI.toString();
                                xxformsUsername = null;
                                xxformsPassword = null;

                                try {
                                    try {
                                        // Handle connection errors
                                        if (connectionResult.statusCode != 200) {
                                            throw new OXFException("Got invalid return code while loading instance: " + instanceResource + ", " + connectionResult.statusCode);
                                        }

                                        // TODO: Handle validating and handleXInclude!

                                        // Read result as XML
                                        if (!isReadonlyHint) {
                                            instanceDocument = TransformerUtils.readDom4j(connectionResult.getResponseInputStream(), connectionResult.resourceURI, false);
                                        } else {
                                            instanceDocument = TransformerUtils.readTinyTree(connectionResult.getResponseInputStream(), connectionResult.resourceURI, false);
                                        }
                                    } catch (Exception e) {
                                        final LocationData extendedLocationData = new ExtendedLocationData(locationData, "reading external XForms instance (optimized)", instanceContainerElement);
                                        dispatchXFormsLinkExceptionEvent(pipelineContext, connectionResult, instanceResource, e, instanceContainerElement, extendedLocationData);
                                        break;
                                    }
                                } finally {
                                    // Clean-up
                                    if (connectionResult != null)
                                        connectionResult.close();
                                }

                            } else {
                                // Connect using external protocol

                                // Extension: username and password
                                // NOTE: Those don't use AVTs for now, because XPath expressions in those could access
                                // instances that haven't been loaded yet.
                                xxformsUsername = instanceContainerElement.attributeValue(XFormsConstants.XXFORMS_USERNAME_QNAME);
                                xxformsPassword = instanceContainerElement.attributeValue(XFormsConstants.XXFORMS_PASSWORD_QNAME);

                                final URL absoluteResolvedURL;
                                final String absoluteResolvedURLString;
                                {
                                    final String resolvedURL = XFormsUtils.resolveResourceURL(pipelineContext, instanceContainerElement, instanceResource, false);
                                    final String inputName = ProcessorImpl.getProcessorInputSchemeInputName(resolvedURL);
                                    if (inputName != null) {
                                        // URL is input:*, keep it as is
                                        absoluteResolvedURL = null;
                                        absoluteResolvedURLString = resolvedURL;
                                    } else {
                                        // URL is regular URL, make sure it is absolute
                                        absoluteResolvedURL = NetUtils.createAbsoluteURL(resolvedURL, null, externalContext);
                                        absoluteResolvedURLString = absoluteResolvedURL.toExternalForm();
                                    }
                                }

                                // Get instance from shared cache if possible
                                if (isApplicationSharedHint) {
                                    final SharedXFormsInstance sharedXFormsInstance = XFormsServerSharedInstancesCache.instance().find(pipelineContext, containingDocument, instanceId, modelEffectiveId, absoluteResolvedURLString, xxformsTimeToLive, xxformsValidation);
                                    setInstance(sharedXFormsInstance, false);
                                    continue;
                                }

                                if (containingDocument.getURIResolver() == null || isApplicationSharedHint) {
                                    // Connect directly if there is no resolver or if the instance is globally shared

                                    if (XFormsServer.logger.isDebugEnabled())
                                        containingDocument.logDebug("model", "getting document from URI",
                                                new String[] { "URI", absoluteResolvedURLString });

                                    connectionResult = NetUtils.openConnection(externalContext, containingDocument.getIndentedLogger(),
                                            "GET", absoluteResolvedURL, xxformsUsername, xxformsPassword, null, null, null, null, null);

                                    try {
                                        try {
                                            // Handle connection errors
                                            if (connectionResult.statusCode != 200) {
                                                throw new OXFException("Got invalid return code while loading instance: " + instanceResource + ", " + connectionResult.statusCode);
                                            }

                                            // TODO: Handle validating and XInclude!

                                            // Read result as XML
                                            if (!isReadonlyHint) {
                                                instanceDocument = TransformerUtils.readDom4j(connectionResult.getResponseInputStream(), connectionResult.resourceURI, false);
                                            } else {
                                                instanceDocument = TransformerUtils.readTinyTree(connectionResult.getResponseInputStream(), connectionResult.resourceURI, false);
                                            }
                                        } catch (Exception e) {
                                            final LocationData extendedLocationData = new ExtendedLocationData(locationData, "reading external instance (no resolver)", instanceContainerElement);
                                            dispatchXFormsLinkExceptionEvent(pipelineContext, connectionResult, instanceResource, e, instanceContainerElement, extendedLocationData);
                                            break;
                                        }
                                    } finally {
                                        // Clean-up
                                        if (connectionResult != null)
                                            connectionResult.close();
                                    }

                                } else {
                                    // Optimized case that uses the provided resolver
                                    if (XFormsServer.logger.isDebugEnabled())
                                        containingDocument.logDebug("model", "getting document from resolver",
                                                new String[] { "URI", absoluteResolvedURLString });

                                    try {
                                        // TODO: Handle validating and handleXInclude!

                                        if (!isReadonlyHint) {
                                            instanceDocument = containingDocument.getURIResolver().readURLAsDocument(absoluteResolvedURLString, xxformsUsername, xxformsPassword);
                                        } else {
                                            instanceDocument = containingDocument.getURIResolver().readURLAsDocumentInfo(absoluteResolvedURLString, xxformsUsername, xxformsPassword);
                                        }
                                    } catch (Exception e) {
                                        final LocationData extendedLocationData = new ExtendedLocationData(locationData, "reading external instance (resolver)", instanceContainerElement);
                                        dispatchXFormsLinkExceptionEvent(pipelineContext, new ConnectionResult(absoluteResolvedURLString), instanceResource, e, instanceContainerElement, extendedLocationData);
                                        break;
                                    }
                                }

                                instanceSourceURI = absoluteResolvedURLString;
                            }
                        } else {
                            // Got a blank src attribute, just dispatch xforms-link-exception
                            final LocationData extendedLocationData = new ExtendedLocationData(locationData, "processing XForms instance", instanceContainerElement);
                            final Throwable throwable = new ValidationException("Invalid blank URL specified for instance: " + instanceId, extendedLocationData);
                            containingDocument.dispatchEvent(pipelineContext, new XFormsLinkExceptionEvent(XFormsModel.this, instanceResource, instanceContainerElement, throwable));
                            break;
                        }

                        if (XFormsServer.logger.isDebugEnabled()) {
                            final long submissionTime = System.currentTimeMillis() - startTime;
                            containingDocument.logDebug("model", "done loading instance (including handling returned body)",
                                    new String[] { "instance", instanceId, "time", Long.toString(submissionTime) });
                        }

                        // Set instance and associated information if everything went well
                        setInstanceDocument(instanceDocument, modelEffectiveId, instanceId, instanceSourceURI, xxformsUsername, xxformsPassword, isApplicationSharedHint, xxformsTimeToLive, xxformsValidation);
                    }
                }
            }

            // Call special listener to update instance
            if (instanceConstructListener != null && getInstances() != null) {
                int position = 0;
                final InstanceConstructListener listener = instanceConstructListener;
                // Make sure we don't keep a reference on this in case this is cache (legacy XForms engine)
                instanceConstructListener = null;
                // Use listener to update instances
                for (Iterator i = getInstances().iterator(); i.hasNext(); position++) {
                    listener.updateInstance(position, (XFormsInstance) i.next());
                }
            }

            // 3. P3P (N/A)

            // 4. Instance data is constructed. Evaluate binds:
            //    a. Evaluate nodeset
            //    b. Apply model item properties on nodes
            //    c. Throws xforms-binding-exception if the node has already model item property with same name
            // TODO: a, b, c

            // 5. xforms-rebuild, xforms-recalculate, xforms-revalidate
            doRebuild(pipelineContext);
            doRecalculate(pipelineContext);
            doRevalidate(pipelineContext);

            synchronizeInstanceDataEventState();

        } else if (XFormsEvents.XXFORMS_READY.equals(eventName)) {

            // This is called after xforms-ready events have been dispatched to all models

            final XFormsStaticState staticState = containingDocument.getStaticState();

            if (staticState != null && !staticState.isInitialized()) {
                // The static state is open to adding instances
                if (getInstances() != null) {
                    for (Iterator instanceIterator = getInstances().iterator(); instanceIterator.hasNext();) {
                        final XFormsInstance currentInstance = (XFormsInstance) instanceIterator.next();

                        if (currentInstance instanceof SharedXFormsInstance) {

                            // NOTE: We add all shared instances, even the globally shared ones, and the static state
                            // decides of the amount of information to actually store
                            if (XFormsServer.logger.isDebugEnabled())
                                containingDocument.logDebug("model", "adding read-only instance to static state",
                                    new String[] { "instance", currentInstance.getEffectiveId() });
                            staticState.addSharedInstance((SharedXFormsInstance) currentInstance);
                        }
                        // TODO: something like staticState.hasReset(modelId);
                        // TODO: maybe we won't do this here, but by restoring the initial dynamic state instead
//                        final boolean modelHasReset = false;
//                        else if (modelHasReset) {
//                            if (XFormsServer.logger.isDebugEnabled())
//                                containingDocument.logDebug("model", "adding reset instance to static state",
//                                    new String[] { "instance", currentInstance.getEffectiveId() });
//                            staticState.addSharedInstance(currentInstance.createSharedInstance());
//                        }
                    }
                }
            }

        } else if (XFormsEvents.XFORMS_MODEL_CONSTRUCT_DONE.equals(eventName)) {
            // 4.2.2 The xforms-model-construct-done Event
            // Bubbles: Yes / Cancelable: No / Context Info: None

            // TODO: implicit lazy instance construction

        } else if (XFormsEvents.XFORMS_REBUILD.equals(eventName)) {
            // 4.3.7 The xforms-rebuild Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            doRebuild(pipelineContext);

        } else if (XFormsEvents.XFORMS_RECALCULATE.equals(eventName)) {
            // 4.3.6 The xforms-recalculate Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            doRecalculate(pipelineContext);

        } else if (XFormsEvents.XFORMS_REVALIDATE.equals(eventName)) {
            // 4.3.5 The xforms-revalidate Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            doRevalidate(pipelineContext);

        } else if (XFormsEvents.XFORMS_REFRESH.equals(eventName)) {
            // 4.3.4 The xforms-refresh Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            doRefresh(pipelineContext);

        } else if (XFormsEvents.XFORMS_RESET.equals(eventName)) {
            // 4.3.8 The xforms-reset Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            // TODO
            // "The instance data is reset to the tree structure and values it had immediately
            // after having processed the xforms-ready event."

            // "Then, the events xforms-rebuild, xforms-recalculate, xforms-revalidate and
            // xforms-refresh are dispatched to the model element in sequence."
            containingDocument.dispatchEvent(pipelineContext, new XFormsRebuildEvent(XFormsModel.this));
            containingDocument.dispatchEvent(pipelineContext, new XFormsRecalculateEvent(XFormsModel.this));
            containingDocument.dispatchEvent(pipelineContext, new XFormsRevalidateEvent(XFormsModel.this));
            containingDocument.dispatchEvent(pipelineContext, new XFormsRefreshEvent(XFormsModel.this));

        } else if (XFormsEvents.XFORMS_COMPUTE_EXCEPTION.equals(eventName) || XFormsEvents.XFORMS_LINK_EXCEPTION.equals(eventName)) {
            // 4.5.4 The xforms-compute-exception Event
            // Bubbles: Yes / Cancelable: No / Context Info: Implementation-specific error string.
            // The default action for this event results in the following: Fatal error.

            // 4.5.2 The xforms-link-exception Event
            // Bubbles: Yes / Cancelable: No / Context Info: The URI that failed to load (xsd:anyURI)
            // The default action for this event results in the following: Fatal error.

            final XFormsExceptionEvent exceptionEvent = (XFormsExceptionEvent) event;
            final Throwable throwable = exceptionEvent.getThrowable();
            if (throwable instanceof RuntimeException)
                throw (RuntimeException) throwable;
            else
                throw new ValidationException("Received fatal error event: " + eventName, throwable, (LocationData) modelDocument.getRootElement().getData());
        }
    }

    private void dispatchXFormsLinkExceptionEvent(PipelineContext pipelineContext, ConnectionResult connectionResult, String srcAttribute, Exception e, Element instanceContainerElement, LocationData locationData) {
        final Throwable throwable;
        if (connectionResult != null && connectionResult.resourceURI != null) {
            final ValidationException validationException
                = ValidationException.wrapException(e, new ExtendedLocationData(new LocationData(connectionResult.resourceURI, -1, -1),
                    "reading external instance", instanceContainerElement));
            validationException.addLocationData(locationData);
            throwable = validationException;
        } else {
            throwable = ValidationException.wrapException(e, locationData);
        }
        containingDocument.dispatchEvent(pipelineContext, new XFormsLinkExceptionEvent(XFormsModel.this, srcAttribute, instanceContainerElement, throwable));
    }

    public static class EventSchedule {

        public static final int VALUE = 1;
        public static final int REQUIRED = 2;
        public static final int RELEVANT = 4;
        public static final int READONLY = 8;
        public static final int VALID = 16;

        public static final int RELEVANT_BINDING = 32;

        public static final int ALL = VALUE | REQUIRED | RELEVANT | READONLY | VALID;

        private String effectiveControlId;
        private int type;
        private XFormsControl xformsControl;

        /**
         * Regular constructor.
         */
        public EventSchedule(String effectiveControlId, int type) {
            this.effectiveControlId = effectiveControlId;
            this.type = type;
        }

        /**
         * This special constructor allows passing an XFormsControl we know will become obsolete. This is currently the
         * only way we have to dispatch events to controls that have "disappeared".
         */
        public EventSchedule(String effectiveControlId, int type, XFormsControl xformsControl) {
            this(effectiveControlId, type);
            this.xformsControl = xformsControl;
        }

        public void updateType(int type) {
            if (this.type == RELEVANT_BINDING) {
                // NOP: all events will be sent
            } else {
                // Combine with existing events
                this.type |= type;
            }
        }

        public int getType() {
            return type;
        }

        public String getEffectiveControlId() {
            return effectiveControlId;
        }

        public XFormsControl getXFormsControl() {
            return xformsControl;
        }
    }

    public void synchronizeInstanceDataEventState() {
        if (instances != null) {
            for (Iterator i = instances.iterator(); i.hasNext();) {
                final XFormsInstance currentInstance = (XFormsInstance) i.next();
                currentInstance.synchronizeInstanceDataEventState();
            }
        }
    }

    public void doRebuild(PipelineContext pipelineContext) {

        if (XFormsServer.logger.isDebugEnabled())
            containingDocument.logDebug("model", "performing rebuild", new String[] { "model id", getEffectiveId() });

        // Rebuild bind tree
        binds.rebuild(pipelineContext);
        // TODO: rebuild computational dependency data structures

        // Controls may have @bind or xxforms:bind() references, so we need to mark them as dirty. Will need dependencies for controls to fix this.
        containingDocument.getXFormsControls().markDirtySinceLastRequest();

        // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
        // have an immediate effect, and clear the corresponding flag."
        if (deferredActionContext != null)
            deferredActionContext.rebuild = false;
    }

    public void doRecalculate(PipelineContext pipelineContext) {

        if (XFormsServer.logger.isDebugEnabled())
            containingDocument.logDebug("model", "performing recalculate", new String[] { "model id", getEffectiveId() });

        if (instances != null) {
            // NOTE: we do not correctly handle computational dependencies, but it doesn't hurt
            // to evaluate "calculate" binds before the other binds.

            final long recalculateStartTime = XFormsServer.logger.isDebugEnabled() ? System.currentTimeMillis() : 0;

            // Apply calculate binds
            binds.applyCalculateBinds(pipelineContext);

            if (XFormsServer.logger.isDebugEnabled()) {
                final long recalculateTime = System.currentTimeMillis() - recalculateStartTime;
                containingDocument.logDebug("model", "done recalculating", new String[] { "time", Long.toString(recalculateTime) });
            }
        }

        // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
        // have an immediate effect, and clear the corresponding flag."
        if (deferredActionContext != null)
            deferredActionContext.recalculate = false;
    }


    public void doRevalidate(final PipelineContext pipelineContext) {

        if (XFormsServer.logger.isDebugEnabled())
            containingDocument.logDebug("model", "performing revalidate", new String[] { "model id", getEffectiveId() });

        if (instances != null) {
            final long revalidateStartTime = XFormsServer.logger.isDebugEnabled() ? System.currentTimeMillis() : 0;

            // Clear validation state
            for (Iterator i = instances.iterator(); i.hasNext();) {
                XFormsUtils.iterateInstanceData(((XFormsInstance) i.next()), new XFormsUtils.InstanceWalker() {
                    public void walk(NodeInfo nodeInfo) {
                        InstanceData.clearValidationState(nodeInfo);
                    }
                }, true);
            }

            // Run validation
            final Map invalidInstances = new HashMap();
            applySchemasIfNeeded(invalidInstances);
            binds.applyValidationBinds(pipelineContext, invalidInstances);

            // NOTE: It is possible, with binds and the use of xxforms:instance(), that some instances in
            // invalidInstances do not belong to this model. Those instances won't get events with the dispatching
            // algorithm below.
            for (Iterator i = instances.iterator(); i.hasNext();) {
                final XFormsInstance instance = (XFormsInstance) i.next();
                if (invalidInstances.get(instance.getEffectiveId()) == null) {
                    containingDocument.dispatchEvent(pipelineContext, new XXFormsValidEvent(instance));
                } else {
                    containingDocument.dispatchEvent(pipelineContext, new XXFormsInvalidEvent(instance));
                }
            }

            if (XFormsServer.logger.isDebugEnabled()) {
                final long revalidateTime = System.currentTimeMillis() - revalidateStartTime;
                containingDocument.logDebug("model", "done revalidating", new String[] { "model id", getEffectiveId(), "time", Long.toString(revalidateTime) });
            }
        }

        // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
        // have an immediate effect, and clear the corresponding flag."
        if (deferredActionContext != null)
            deferredActionContext.revalidate = false;
    }

    public void doRefresh(final PipelineContext pipelineContext) {
        // "1. All UI bindings should be reevaluated as necessary."

        // "2. A node can be changed by confirmed user input to a form control, by
        // xforms-recalculate (section 4.3.6) or by the setvalue (section 10.1.9) action. If the
        // value of an instance data node was changed, then the node must be marked for
        // dispatching the xforms-value-changed event."

        // "3. If the xforms-value-changed event is marked for dispatching, then all of the
        // appropriate model item property notification events must also be marked for
        // dispatching (xforms-optional or xforms-required, xforms-readwrite or xforms-readonly,
        // and xforms-enabled or xforms-disabled)."

        // "4. For each form control, each notification event that is marked for dispatching on
        // the bound node must be dispatched (xforms-value-changed, xforms-valid,
        // xforms-invalid, xforms-optional, xforms-required, xforms-readwrite, xforms-readonly,
        // and xforms-enabled, xforms-disabled). The notification events xforms-out-of-range or
        // xforms-in-range must also be dispatched as appropriate. This specification does not
        // specify an ordering for the events."

        // This just handles the legacy XForms engine which doesn't use the controls
        final XFormsControls xformsControls = containingDocument.getXFormsControls();
        if (xformsControls == null || xformsControls.getCurrentControlsState() == null)
            return;

        if (XFormsServer.logger.isDebugEnabled())
            containingDocument.logDebug("model", "performing refresh", new String[] { "model id", getEffectiveId() });

        // If this is the first refresh we mark nodes to dispatch MIP events
        final boolean isMustMarkMIPEvents = containingDocument.isInitializationFirstRefreshClear();

        // Rebuild controls if needed
        xformsControls.rebuildCurrentControlsStateIfNeeded(pipelineContext);

        // Obtain global information about event handlers. This is a rough optimization so we can avoid sending certain
        // types of events below.
        final boolean isMustSendValueChangedEvents = xformsControls.hasHandlerForEvent(XFormsEvents.XFORMS_VALUE_CHANGED);
        final boolean isMustSendRequiredEvents = xformsControls.hasHandlerForEvent(XFormsEvents.XFORMS_REQUIRED) || xformsControls.hasHandlerForEvent(XFormsEvents.XFORMS_OPTIONAL);
        final boolean isMustSendRelevantEvents = xformsControls.hasHandlerForEvent(XFormsEvents.XFORMS_ENABLED) || xformsControls.hasHandlerForEvent(XFormsEvents.XFORMS_DISABLED);
        final boolean isMustSendReadonlyEvents = xformsControls.hasHandlerForEvent(XFormsEvents.XFORMS_READONLY) || xformsControls.hasHandlerForEvent(XFormsEvents.XFORMS_READWRITE);
        final boolean isMustSendValidEvents = xformsControls.hasHandlerForEvent(XFormsEvents.XFORMS_VALID) || xformsControls.hasHandlerForEvent(XFormsEvents.XFORMS_INVALID);

        final boolean isMustSendUIEvents = isMustSendValueChangedEvents || isMustSendRequiredEvents || isMustSendRelevantEvents || isMustSendReadonlyEvents || isMustSendValidEvents;
        if (isMustSendUIEvents) {
            // There are potentially event handlers for UI events, so do the whole processing

            // Build list of events to send
            final Map relevantBindingEvents = xformsControls.getCurrentControlsState().getEventsToDispatch();
            final List eventsToDispatch = new ArrayList();

            // Iterate through controls and check the nodes they are bound to
            xformsControls.visitAllControls(new XFormsControls.XFormsControlVisitorListener() {
                public void startVisitControl(XFormsControl control) {

                    // This can happen if control is not bound to anything (includes xforms:group[not(@ref) and not(@bind)])
                    final NodeInfo currentNodeInfo = control.getBoundNode();
                    if (currentNodeInfo == null)
                        return;

                    // We only dispatch events for controls bound to a mutable document
                    if (!(currentNodeInfo instanceof NodeWrapper))
                        return;

                    // Check if value has changed
                    final boolean isValueControl = XFormsControlFactory.isValueControl(control.getName());
                    final boolean valueChanged = isValueControl && InstanceData.isValueChanged(currentNodeInfo);

                    final String effectiveId = control.getEffectiveId();
                    final EventSchedule existingEventSchedule = (relevantBindingEvents == null) ? null : (EventSchedule) relevantBindingEvents.get(effectiveId);

                    if (valueChanged && isMustSendValueChangedEvents) {
                        // Value change takes care of everything
                        // NOTE: isValueControl is implied
                        addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.ALL);
                    } else if (valueChanged) {
                        // Must do "as if" we send all the MIP events
                        // NOTE: isValueControl is implied
                        if (isMustSendRequiredEvents)
                            addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.REQUIRED);
                        if (isMustSendRelevantEvents)
                            addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.RELEVANT);
                        if (isMustSendReadonlyEvents)
                            addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.READONLY);
                        if (isMustSendValidEvents)
                            addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.VALID);
                    } else {
                        // Dispatch xforms-optional/xforms-required if needed
                        if (isValueControl && isMustSendRequiredEvents) { // do this only for value controls
                            if (isMustMarkMIPEvents) {
                                // Send in all cases
                                addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.REQUIRED);
                            } else {
                                // Send only when value has changed
                                final boolean previousRequiredState = InstanceData.getPreviousRequiredState(currentNodeInfo);
                                final boolean newRequiredState = InstanceData.getRequired(currentNodeInfo);

                                if ((previousRequiredState && !newRequiredState) || (!previousRequiredState && newRequiredState)) {
                                    addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.REQUIRED);
                                }
                            }
                        }
                        // Dispatch xforms-enabled/xforms-disabled if needed
                        if (isMustSendRelevantEvents) {

                            if (isMustMarkMIPEvents) {
                                // Send in all cases
                                addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.RELEVANT);
                            } else {
                                // Send only when value has changed
                                final boolean previousRelevantState = InstanceData.getPreviousInheritedRelevantState(currentNodeInfo);
                                final boolean newRelevantState = InstanceData.getInheritedRelevant(currentNodeInfo);

                                if ((previousRelevantState && !newRelevantState) || (!previousRelevantState && newRelevantState)) {
                                    addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.RELEVANT);
                                }
                            }
                        }
                        // Dispatch xforms-readonly/xforms-readwrite if needed
                        if (isMustSendReadonlyEvents) {
                            if (isMustMarkMIPEvents) {
                                // Send in all cases
                                addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.READONLY);
                            } else {
                                final boolean previousReadonlyState = InstanceData.getPreviousInheritedReadonlyState(currentNodeInfo);
                                final boolean newReadonlyState = InstanceData.getInheritedReadonly(currentNodeInfo);

                                if ((previousReadonlyState && !newReadonlyState) || (!previousReadonlyState && newReadonlyState)) {
                                    addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.READONLY);
                                }
                            }
                        }

                        // Dispatch xforms-valid/xforms-invalid if needed

                        // NOTE: There is no mention in the spec that these events should be displatched automatically
                        // when the value has changed, contrary to the other events above.
                        if (isValueControl && isMustSendValidEvents) { // do this only for value controls
                            if (isMustMarkMIPEvents) {
                                // Send in all cases
                                addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.VALID);
                            } else {
                                final boolean previousValidState = InstanceData.getPreviousValidState(currentNodeInfo);
                                final boolean newValidState = InstanceData.getValid(currentNodeInfo);

                                if ((previousValidState && !newValidState) || (!previousValidState && newValidState)) {
                                    addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.VALID);
                                }
                            }
                        }
                    }
                }

                public void endVisitControl(XFormsControl XFormsControl) {
                }

                private void addEventToSchedule(EventSchedule eventSchedule, String effectiveControlId, int type) {
                    if (eventSchedule == null)
                        eventsToDispatch.add(new EventSchedule(effectiveControlId, type));
                    else
                        eventSchedule.updateType(type);
                }
            });

            // Clear InstanceData event state
            // NOTE: We clear for all models, as we are processing refresh events for all models here. This may have to be changed in the future.
            containingDocument.synchronizeInstanceDataEventState();
            xformsControls.getCurrentControlsState().setEventsToDispatch(null);

            // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
            // have an immediate effect, and clear the corresponding flag."
            if (deferredActionContext != null)
                deferredActionContext.refresh = false;

            // Add "relevant binding" events
            if (relevantBindingEvents != null)
                eventsToDispatch.addAll(relevantBindingEvents.values());

            // Send events and (try to) make sure the event corresponds to the current instance data
            // NOTE: event order and the exact steps to take are under-specified in 1.0.
            for (Iterator i = eventsToDispatch.iterator(); i.hasNext();) {
                final EventSchedule eventSchedule = (XFormsModel.EventSchedule) i.next();

                final String controlInfoId = eventSchedule.getEffectiveControlId();
                final int type = eventSchedule.getType();
                final boolean isRelevantBindingEvent = (type & EventSchedule.RELEVANT_BINDING) != 0;

                final XFormsControl xformsControl = (XFormsControl) xformsControls.getObjectByEffectiveId(controlInfoId);

                if (!isRelevantBindingEvent) {
                    // Regular type of event

                    if (xformsControl == null) {
                        // In this case, the algorithm in the spec is not clear. Many thing can have happened between the
                        // initial determination of a control bound to a changing node, and now, including many events and
                        // actions.
                        continue;
                    }

                    // Re-obtain node to which control is bound, in case things have changed (includes xforms:group[not(@ref) and not(@bind)])
                    final NodeInfo currentNodeInfo = xformsControl.getBoundNode();
                    if (currentNodeInfo == null) {
                        // See comment above about things that can have happened since.
                        continue;
                    }

                    // We only dispatch events for controls bound to a mutable document
                    if (!(currentNodeInfo instanceof NodeWrapper))
                        continue;

                    // Is this a value control?
                    final boolean isValueControl = XFormsControlFactory.isValueControl(xformsControl.getName());

                    // "The XForms processor is not considered to be executing an outermost action handler at the time that it
                    // performs deferred update behavior for XForms models. Therefore, event handlers for events dispatched to
                    // the user interface during the deferred refresh behavior are considered to be new outermost action
                    // handler."

                    if (isValueControl && isMustSendValueChangedEvents && (type & EventSchedule.VALUE) != 0) { // do this only for value controls
                        containingDocument.dispatchEvent(pipelineContext, new XFormsValueChangeEvent(xformsControl));
                    }
                    // TODO: after each event, we should get a new reference to the control as it may have changed
                    if (currentNodeInfo != null && currentNodeInfo instanceof NodeWrapper) {
                        if (isValueControl && isMustSendRequiredEvents && (type & EventSchedule.REQUIRED) != 0) { // do this only for value controls
                            final boolean currentRequiredState = InstanceData.getRequired(currentNodeInfo);
                            if (currentRequiredState) {
                                containingDocument.dispatchEvent(pipelineContext, new XFormsRequiredEvent(xformsControl));
                            } else {
                                containingDocument.dispatchEvent(pipelineContext, new XFormsOptionalEvent(xformsControl));
                            }
                        }
                        if (isMustSendRelevantEvents && (type & EventSchedule.RELEVANT) != 0) {
                            final boolean currentRelevantState = InstanceData.getInheritedRelevant(currentNodeInfo);
                            if (currentRelevantState) {
                                containingDocument.dispatchEvent(pipelineContext, new XFormsEnabledEvent(xformsControl));
                            } else {
                                containingDocument.dispatchEvent(pipelineContext, new XFormsDisabledEvent(xformsControl));
                            }
                        }
                        if (isMustSendReadonlyEvents && (type & EventSchedule.READONLY) != 0) {
                            final boolean currentReadonlyState = InstanceData.getInheritedReadonly(currentNodeInfo);
                            if (currentReadonlyState) {
                                containingDocument.dispatchEvent(pipelineContext, new XFormsReadonlyEvent(xformsControl));
                            } else {
                                containingDocument.dispatchEvent(pipelineContext, new XFormsReadwriteEvent(xformsControl));
                            }
                        }
                        if (isValueControl && isMustSendValidEvents && (type & EventSchedule.VALID) != 0) { // do this only for value controls
                            final boolean currentValidState = InstanceData.getValid(currentNodeInfo);
                            if (currentValidState) {
                                containingDocument.dispatchEvent(pipelineContext, new XFormsValidEvent(xformsControl));
                            } else {
                                containingDocument.dispatchEvent(pipelineContext, new XFormsInvalidEvent(xformsControl));
                            }
                        }
                    }
                } else {
                    // Handle special case of "relevant binding" events, i.e. relevance that changes because a node becomes
                    // bound or unbound to a node.

                    if (xformsControl != null) {

                        // If control is not bound (e.g. xforms:group[not(@ref) and not(@bind)]) no events are sent
                        final boolean isControlBound = xformsControl.getBindingContext().isNewBind();
                        if (!isControlBound)
                            continue;

                        // Is this a value control?
                        final boolean isValueControl = XFormsControlFactory.isValueControl(xformsControl.getName());

                        // Re-obtain node to which control is bound, in case things have changed
                        final NodeInfo currentNodeInfo = xformsControl.getBoundNode();
                        if (currentNodeInfo != null) {

                            // We only dispatch value-changed and other events for controls bound to a mutable document
                            if (!(currentNodeInfo instanceof NodeWrapper))
                                continue;

                            final boolean currentRelevantState = InstanceData.getInheritedRelevant(currentNodeInfo);
                            if (currentRelevantState) {
                                // The control is newly bound to a relevant node
                                if (isMustSendRelevantEvents) {
                                    containingDocument.dispatchEvent(pipelineContext, new XFormsEnabledEvent(xformsControl));
                                }

                                // Also send other MIP events
                                if (isMustSendRequiredEvents && isValueControl) { // do this only for value controls
                                    final boolean currentRequiredState = InstanceData.getRequired(currentNodeInfo);
                                    if (currentRequiredState) {
                                        containingDocument.dispatchEvent(pipelineContext, new XFormsRequiredEvent(xformsControl));
                                    } else {
                                        containingDocument.dispatchEvent(pipelineContext, new XFormsOptionalEvent(xformsControl));
                                    }
                                }

                                if (isMustSendReadonlyEvents) {
                                    final boolean currentReadonlyState = InstanceData.getInheritedReadonly(currentNodeInfo);
                                    if (currentReadonlyState) {
                                        containingDocument.dispatchEvent(pipelineContext, new XFormsReadonlyEvent(xformsControl));
                                    } else {
                                        containingDocument.dispatchEvent(pipelineContext, new XFormsReadwriteEvent(xformsControl));
                                    }
                                }

                                if (isMustSendValidEvents && isValueControl) { // do this only for value controls
                                    final boolean currentValidState = InstanceData.getValid(currentNodeInfo);
                                    if (currentValidState) {
                                        containingDocument.dispatchEvent(pipelineContext, new XFormsValidEvent(xformsControl));
                                    } else {
                                        containingDocument.dispatchEvent(pipelineContext, new XFormsInvalidEvent(xformsControl));
                                    }
                                }
                            }
                        } else {
                            // The control is not bound to a node
                            sendDefaultEventsForDisabledControl(pipelineContext, xformsControl, isValueControl,
                                    isMustSendRequiredEvents, isMustSendRelevantEvents, isMustSendReadonlyEvents, isMustSendValidEvents);
                        }
                    } else {
                        // The control no longer exists
                        if (eventSchedule.getXFormsControl() != null) {
                            // Is this a value control?
                            final boolean isValueControl = XFormsControlFactory.isValueControl(eventSchedule.getXFormsControl().getName());
                            sendDefaultEventsForDisabledControl(pipelineContext, eventSchedule.getXFormsControl(), isValueControl,
                                    isMustSendRequiredEvents, isMustSendRelevantEvents, isMustSendReadonlyEvents, isMustSendValidEvents);
                        }
                    }
                }
            }
        } else {
            // No UI events to send because there is no event handlers for any of them
            containingDocument.logDebug("model", "refresh skipping sending of UI events because no listener was found", new String[] { "model id", getEffectiveId() });

            // NOTE: We clear for all models, as we are processing refresh events for all models here. This may have to be changed in the future.
            containingDocument.synchronizeInstanceDataEventState();
            xformsControls.getCurrentControlsState().setEventsToDispatch(null);

            // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
            // have an immediate effect, and clear the corresponding flag."
            if (deferredActionContext != null)
                deferredActionContext.refresh = false;
        }

        // "5. The user interface reflects the state of the model, which means that all forms
        // controls reflect for their corresponding bound instance data:"
//        if (xformsControls != null) {
//            xformsControls.refreshForModel(pipelineContext, this);
//        }
    }

    private void sendDefaultEventsForDisabledControl(PipelineContext pipelineContext, XFormsControl xformsControl, boolean isValueControl,
                                                     boolean isMustSendRequiredEvents, boolean isMustSendRelevantEvents,
                                                     boolean isMustSendReadonlyEvents, boolean isMustSendValidEvents) {

        // Control is disabled
        if (isMustSendRelevantEvents)
            containingDocument.dispatchEvent(pipelineContext, new XFormsDisabledEvent(xformsControl));

        // Send events for default MIP values
        if (isMustSendRequiredEvents && isValueControl) // do this only for value controls
            containingDocument.dispatchEvent(pipelineContext, new XFormsOptionalEvent(xformsControl));

        if (isMustSendReadonlyEvents)
            containingDocument.dispatchEvent(pipelineContext, new XFormsReadwriteEvent(xformsControl));

        if (isMustSendValidEvents && isValueControl) // do this only for value controls
            containingDocument.dispatchEvent(pipelineContext, new XFormsValidEvent(xformsControl));
    }

    /**
     * Handle events related to externally updating one or more instance documents.
     */
    public void handleNewInstanceDocuments(PipelineContext pipelineContext, final XFormsInstance newInstance) {

        // Set the instance on this model
        setInstance(newInstance, true);

        // The controls will be dirty
        containingDocument.getXFormsControls().markDirtySinceLastRequest();

        // NOTE: The current spec specifies direct calls, but it might be updated to require setting flags instead.
        setAllDeferredFlags(true);

        // Mark new instance nodes to which controls are bound for event dispatching
        if (!newInstance.isReadOnly()) {// replacing a read-only instance does not cause value change events at the moment

            final XFormsControls xformsControls = containingDocument.getXFormsControls();
            if (xformsControls != null && xformsControls.getCurrentControlsState() != null) {// this just handles the legacy XForms engine which doesn't use the controls

                if (XFormsServer.logger.isDebugEnabled())
                    containingDocument.logDebug("model", "marking nodes for value change following instance replacement",
                            new String[] { "isntance id", newInstance.getEffectiveId() });

                // Rebuild controls if needed
                // NOTE: This requires recalculate and revalidate to take place for 1) relevance handling and 2) type handling
                doRebuild(pipelineContext);
                doRecalculate(pipelineContext);
                doRevalidate(pipelineContext);
                xformsControls.rebuildCurrentControlsStateIfNeeded(pipelineContext);

                // Mark all nodes to which value controls are bound
                xformsControls.visitAllControls(new XFormsControls.XFormsControlVisitorListener() {
                    public void startVisitControl(XFormsControl control) {

                        // Don't do anything if it's not a value control
                        final boolean isValueControl = XFormsControlFactory.isValueControl(control.getName());
                        if (!isValueControl)
                            return;

                        // This can happen if control is not bound to anything (includes xforms:group[not(@ref) and not(@bind)])
                        final NodeInfo currentNodeInfo = control.getBoundNode();
                        if (currentNodeInfo == null)
                            return;

                        // We only mark nodes in mutable documents
                        if (!(currentNodeInfo instanceof NodeWrapper))
                            return;

                        // We only mark nodes in the replaced instance
                        if (getInstanceForNode(currentNodeInfo) != newInstance)
                            return;

                        // Finally, mark node
                        InstanceData.markValueChanged(currentNodeInfo);
                    }

                    public void endVisitControl(XFormsControl xformsControl) {
                    }
                });
            }
        }
    }

    private DeferredActionContext deferredActionContext;

    public static class DeferredActionContext {
        public boolean rebuild;
        public boolean recalculate;
        public boolean revalidate;
        public boolean refresh;

        public void setAllDeferredFlags(boolean value) {
            rebuild = value;
            recalculate = value;
            revalidate = value;
            refresh = value;
        }
    }

    public DeferredActionContext getDeferredActionContext() {
        return deferredActionContext;
    }

    public void setAllDeferredFlags(boolean value) {
        if (deferredActionContext != null)
            deferredActionContext.setAllDeferredFlags(value);
    }

    public void startOutermostActionHandler() {
        if (deferredActionContext == null)
            deferredActionContext = new DeferredActionContext();
    }

    public void endOutermostActionHandler(PipelineContext pipelineContext) {

        // TODO: This is not 100% in line with the "correct" interpretation of the deferred updates, as deferred
        // behavior is triggered at the level of outermost action handlers, not outermost event dispatches.

        // Process deferred behavior
        final DeferredActionContext currentDeferredActionContext = deferredActionContext;
        deferredActionContext = null;
        if (currentDeferredActionContext != null) {
            if (currentDeferredActionContext.rebuild) {
                containingDocument.startOutermostActionHandler();
                containingDocument.dispatchEvent(pipelineContext, new XFormsRebuildEvent(this));
                containingDocument.endOutermostActionHandler(pipelineContext);
            }
            if (currentDeferredActionContext.recalculate) {
                containingDocument.startOutermostActionHandler();
                containingDocument.dispatchEvent(pipelineContext, new XFormsRecalculateEvent(this));
                containingDocument.endOutermostActionHandler(pipelineContext);
            }
            if (currentDeferredActionContext.revalidate) {
                containingDocument.startOutermostActionHandler();
                containingDocument.dispatchEvent(pipelineContext, new XFormsRevalidateEvent(this));
                containingDocument.endOutermostActionHandler(pipelineContext);
            }
            if (currentDeferredActionContext.refresh) {
                containingDocument.startOutermostActionHandler();
                containingDocument.dispatchEvent(pipelineContext, new XFormsRefreshEvent(this));
                containingDocument.endOutermostActionHandler(pipelineContext);
            }
        }
    }

    /**
     * This class is cloneable.
     */
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    public static interface InstanceConstructListener {
        public void updateInstance(int position, XFormsInstance instance);
    }

    public void setInstanceConstructListener(InstanceConstructListener instanceConstructListener) {
        this.instanceConstructListener = instanceConstructListener;
    }

    public XFormsEventHandlerContainer getParentEventHandlerContainer(XFormsContainingDocument containingDocument) {
        return this.containingDocument;
    }

    /**
     * Return the List of XFormsEventHandler objects within this object.
     */
    public List getEventHandlers(XFormsContainingDocument containingDocument) {
        // Do test on null for legacy XForm (no event handlers in the model are supported with the legacy engine)
        final XFormsStaticState staticState = containingDocument.getStaticState();
        return (staticState != null) ? staticState.getEventHandlers(getEffectiveId()) : null;
    }
}
