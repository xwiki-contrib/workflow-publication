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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.refactoring.event.DocumentCopiedEvent;
import org.xwiki.refactoring.job.CopyRequest;
import org.xwiki.workflowpublication.PublicationWorkflow;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Event listener to listen to document copy events and remove the workflow object from the copy, to avoid having two
 * pages with the same target. We trust the person doing the copy to set up a new workflow if desired, instead of making
 * guesses on our own.
 *
 * @version $Id$
 * @since 1.9
 */
@Component
@Named("PublicationWorkflowCopyListener")
@Singleton
public class PublicationWorkflowCopyListener implements EventListener
{
    private static final List<Event> EVENTS = Collections.singletonList(new DocumentCopiedEvent());

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private PublicationWorkflow publicationWorkflow;

    /**
     * {@inheritDoc}
     *
     * @see org.xwiki.observation.EventListener#getEvents()
     */
    public List<Event> getEvents()
    {
        return EVENTS;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.xwiki.observation.EventListener#getName()
     */
    public String getName()
    {
        return "PublicationWorkflowCopyListener";
    }

    public void onEvent(Event event, Object source, Object data)
    {
        XWikiContext context = contextProvider.get();
        String wikiId = context.getWikiId();

        try {
            CopyRequest copyRequest = (CopyRequest) data;
            DocumentReference workflowDestinationRef = (DocumentReference) copyRequest.getDestination();

            // Set the context wiki to current wiki as the DocumentCopyingEvent is executed with the main wiki context.
            context.setWikiId(Objects.requireNonNull(workflowDestinationRef).getWikiReference().getName());

            XWikiDocument workflowDestinationDoc = context.getWiki().getDocument(workflowDestinationRef, context);
            if (publicationWorkflow.isWorkflowDocument(workflowDestinationDoc, context)) {
                workflowDestinationDoc.removeXObjects(publicationWorkflow.PUBLICATION_WORKFLOW_CLASS);
            }
        } catch (XWikiException e) {
            throw new RuntimeException(e);
        } finally {
            // Set back the context wiki to original (main).
            context.setWikiId(wikiId);
        }
    }
}
