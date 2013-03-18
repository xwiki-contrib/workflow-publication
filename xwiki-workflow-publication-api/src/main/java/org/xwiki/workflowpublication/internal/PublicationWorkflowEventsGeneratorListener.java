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

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatingEvent;
import org.xwiki.bridge.event.DocumentUpdatingEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.ObservationManager;
import org.xwiki.observation.event.Event;
import org.xwiki.workflowpublication.DocumentPublishingEvent;
import org.xwiki.workflowpublication.PublicationWorkflow;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.web.Utils;

/**
 * @version $Id$
 */
@Component("publicationworkfloweventsgenerator")
public class PublicationWorkflowEventsGeneratorListener implements EventListener
{
    public final static String CONTEXTKEY_PUBLISHING = "publicationworkflow:publish";

    @Inject
    protected PublicationWorkflow publicationWorkflow;

    @Inject
    private Logger logger;

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#getName()
     */
    @Override
    public String getName()
    {
        return "publicationworkfloweventsgenerator";
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#getEvents()
     */
    @Override
    public List<Event> getEvents()
    {
        return Arrays.<Event> asList(new DocumentCreatingEvent(), new DocumentUpdatingEvent());
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#onEvent(org.xwiki.observation.event.Event, java.lang.Object,
     *      java.lang.Object)
     */
    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        XWikiContext xcontext = (XWikiContext) data;
        XWikiDocument doc = (XWikiDocument) source;

        Object publishingContextKey = xcontext.get(CONTEXTKEY_PUBLISHING);
        try {
            if (publishingContextKey != null && Boolean.parseBoolean(publishingContextKey.toString())
                && publicationWorkflow.isWorkflowDocument(doc, xcontext)) {
                ObservationManager observation = Utils.getComponent(ObservationManager.class);
                observation.notify(new DocumentPublishingEvent(doc.getDocumentReference()), doc, xcontext);
            }
        } catch (XWikiException e) {
            logger.warn(
                "Could not find out if the document is a workflow document to generate publishing event for document "
                    + doc.getDocumentReference(), e);
        }
    }
}
