/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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
package org.xwiki.workflowpublication.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentUpdatingEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.workflowpublication.PublicationRoles;
import org.xwiki.workflowpublication.PublicationWorkflow;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.ObjectDiff;

/**
 * Event listener to listen to documents updating events and prevent changing the status by unauthorized users for
 * notion/question documents.
 * 
 * @version $Id$
 */
@Component
@Named("moderation-protection")
@Singleton
public class RolesEnforcerListener implements EventListener
{
    public final static String STATUS_PROPNAME = "status";

    public final static String STATUS_MODERATING = "moderating";

    public final static String STATUS_VALIDATING = "validating";

    public final static String STATUS_DRAFT = "draft";

    public final static String STATUS_PUBLISHED = "published";

    public final static String STATUS_ARCHIVED = "archived";

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

    /**
     * Reference string serializer.
     */
    @Inject
    protected EntityReferenceSerializer<String> stringSerializer;

    @Inject
    @Named("publicationroles")
    protected PublicationRoles publicationRoles;

    @Inject
    protected PublicationWorkflow publicationWorkflow;

    /**
     * The events observed by this observation manager.
     */
    private final List<Event> eventsList = new ArrayList<Event>(Arrays.asList(new DocumentUpdatingEvent()));

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#getEvents()
     */
    public List<Event> getEvents()
    {
        return eventsList;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#getName()
     */
    public String getName()
    {
        return "ModerationProtectionListener";
    }

    /**
     * (the include mace) {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#onEvent(org.xwiki.observation.event.Event, java.lang.Object,
     *      java.lang.Object)
     */
    public void onEvent(Event event, Object source, Object data)
    {
        XWikiDocument currentDocument = (XWikiDocument) source;

        XWikiDocument previousDocument = currentDocument.getOriginalDocument();

        XWikiContext context = (XWikiContext) data;

        // check if the old document is a workflow document, if it is, we need to handle moderation protection
        try {
            if (!publicationWorkflow.isWorkflowDocument(previousDocument, context)) {
                return;
            }
        } catch (XWikiException exc) {
            logger.warn("Could not get workflow config document for document "
                + stringSerializer.serialize(previousDocument.getDocumentReference()));
            return;
        }

        // get the workflow object and get the state, in the new document and in the old document
        boolean isOtherWorkflowChange = false;
        List<List<ObjectDiff>> objectDiffs = currentDocument.getObjectDiff(previousDocument, currentDocument, context);
        for (List<ObjectDiff> objectChanges : objectDiffs) {
            for (ObjectDiff diff : objectChanges) {
                // if a change is in a workflow object, it's a workflow change
                if (diff.getXClassReference().equals(
                    currentReferenceEntityResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS))) {
                    isOtherWorkflowChange = true;
                }
            }
        }

        if (isOtherWorkflowChange) {
            // TODO: restore workflow object besides the status prop
        }
    }
}
