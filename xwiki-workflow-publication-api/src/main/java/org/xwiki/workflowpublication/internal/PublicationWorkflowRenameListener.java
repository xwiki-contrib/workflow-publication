package org.xwiki.workflowpublication.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatingEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.workflowpublication.PublicationRoles;
import org.xwiki.workflowpublication.PublicationWorkflow;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Event listener to listen to documents renaming events and update the target of the draft according to the new location.
 * 
 * @version $Id$
 */
@Component
@Named("PublicationWorkflowRenameListener")
@Singleton
public class PublicationWorkflowRenameListener implements EventListener {
	
	/**
     * The logger to log.
     */
    @Inject
    private Logger logger;
    
    /**
     * The current entity reference resolver, to resolve the notions class reference.
     */
    @Inject
    @Named("current/reference")
    protected DocumentReferenceResolver<EntityReference> currentReferenceEntityResolver;
    
    @Inject
    @Named("explicit")
    protected DocumentReferenceResolver<EntityReference> explicitReferenceDocRefResolver;
    
    @Inject
    protected DocumentReferenceResolver<String> defaultDocRefResolver;
    
    @Inject
    private QueryManager queryManager;
    
    /**
     * The execution, to get the context from it.
     */
    @Inject
    protected Execution execution;

    /**
     * Reference string serializer.
     */
    @Inject
    protected EntityReferenceSerializer<String> stringSerializer;
    
    @Inject
    @Named("compactwiki")
    protected EntityReferenceSerializer<String> compactWikiSerializer;
    
    /**
     * For translations.
     */
    @Inject
    private ContextualLocalizationManager localizationManager;    
	
    public static final String WF_CONFIG_REF_FIELDNAME = "workflow";

    public static final String WF_TARGET_FIELDNAME = "target";

    public final static String WF_STATUS_FIELDNAME = "status";
    
    public final static String WF_STATUS_AUTHOR_FIELDNAME = "statusAuthor";

    public final static String WF_IS_TARGET_FIELDNAME = "istarget";

    public final static String WF_IS_DRAFTSPACE_FIELDNAME = "defaultDraftSpace";

    public final static int DRAFT = 0;

    public final static int PUBLISHED = 1;

    public final static String STATUS_MODERATING = "moderating";

    public final static String STATUS_VALIDATING = "validating";

    public final static String STATUS_VALID = "valid";

    public final static String STATUS_DRAFT = "draft";

    public final static String STATUS_PUBLISHED = "published";
    
	
	@Inject
    protected PublicationWorkflow publicationWorkflow;
	
	@Inject
	protected PublicationRoles publicationRoles;
	
	/**
     * The events observed by this observation manager.
     */
    private final List<Event> eventsList = new ArrayList<Event>(Arrays.asList(new DocumentCreatingEvent()));

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#getEvents()
     */
	public List<Event> getEvents() {
		
		return eventsList;
		
	}

	/**
     * {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#getName()
     */
	public String getName() {
		return "PublicationWorkflowRenameListener";
	}

	/**
     * (the include mace) {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#onEvent(org.xwiki.observation.event.Event, java.lang.Object,
     *      java.lang.Object)
     */
	public void onEvent(Event event, Object source, Object data) {
		
		XWikiDocument currentDocument = (XWikiDocument) source;
		
		XWikiContext context = (XWikiContext) data;

        try {
        	// Check if the old document is a workflow document
            if (!publicationWorkflow.isWorkflowDocument(currentDocument, context)) {
                return;
            }
            
            DocumentReference targetRef = currentDocument.getDocumentReference();            
            
            // Get the workflow object
            BaseObject workflowInstance =
            		currentDocument.getXObject(explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS,
            				targetRef));
            
            // Check if the document is published, is target and the current user can validate it
            if(StringUtils.equals(workflowInstance.getStringValue(WF_STATUS_FIELDNAME), STATUS_PUBLISHED) && 
            		workflowInstance.getIntValue(WF_IS_TARGET_FIELDNAME) ==  PUBLISHED && 
            		publicationRoles.canValidate(context.getUserReference(), currentDocument, context)) {
            	
            	// Get the target value
            	String serializedOldTargetName = workflowInstance.getStringValue(WF_TARGET_FIELDNAME);
            	
            	// Set the current document as target
            	String serializedNewTargetName = compactWikiSerializer.serialize(targetRef, context.getWiki());
            	workflowInstance.setStringValue(WF_TARGET_FIELDNAME, serializedNewTargetName);
            	
            	// Searching for the draft
            	List<Object> queryParams = new ArrayList<Object>();
            	String workflowsQuery =
                        "select obj.name from BaseObject obj, StringProperty target, IntegerProperty istarget where "
                            + "obj.className = ? and obj.id = target.id.id and target.id.name = ? and target.value = ? and "
                            + "obj.id = istarget.id.id and istarget.id.name = ? and istarget.value = 0";
            	
            	queryParams.add(compactWikiSerializer.serialize(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS));
            	queryParams.add(WF_TARGET_FIELDNAME);
            	queryParams.add(serializedOldTargetName);
            	queryParams.add(WF_IS_TARGET_FIELDNAME);
            	
            	Query query = queryManager.createQuery(workflowsQuery, Query.HQL);
                query.bindValues(queryParams);
                List<String> results = query.execute();
            	
                // Update the target value in the draft
            	for (String result : results) {
            		DocumentReference docRef = this.defaultDocRefResolver.resolve(result, context.getWikiReference());
            		XWikiDocument draftDocument = context.getWiki().getDocument(docRef, context);
            		
            		// Get the workflow object
                    BaseObject draftWorkflowInstance =
                    		draftDocument.getXObject(explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS,
                    				targetRef));
                    draftWorkflowInstance.setStringValue(WF_TARGET_FIELDNAME, serializedNewTargetName);
                    
                    // Save the draft document
                    String defaultMessage = "The target was moved / renamed. Set the new location to " + serializedNewTargetName + ".";
                    String message =
                            getMessage("workflow.move.updateDraft", defaultMessage,
                                    Arrays.asList(serializedNewTargetName.toString()));
                    context.getWiki().saveDocument(draftDocument, message, false, context);
            	}

            	
            }
           
        } catch (XWikiException e) {
            logger.warn("Could not get workflow config document for document "
                + stringSerializer.serialize(currentDocument.getDocumentReference()));
            return;
        } catch (QueryException e) {
        	logger.warn("Could not get draft for document "
                    + stringSerializer.serialize(currentDocument.getDocumentReference()));
        	return;
		}
		
	}
	
	/**
     * @param key Translation key
     * @param params Parameters to include in the translation
     * @param defaultMessage Message to display if the message tool finds no translation
     * @return message to use
     */
    protected String getMessage(String key, String defaultMessage, List<String> params)
    {
        String message = (params == null)
                ? localizationManager.getTranslationPlain(key)
                : localizationManager.getTranslationPlain(key, params.toArray());
        if (message == null || message.equals(key)) {
            message = defaultMessage;
        }
        // trim the message, whichever that is, to 255 characters, otherwise we're in trouble
        if (message.length() > 255) {
            // add some dots to show that it was trimmed
            message = message.substring(0, 252) + "...";
        }
        return message;
    }
}
