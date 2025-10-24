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
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import com.xpn.xwiki.XWikiContext;

@ComponentTest
// @ComponentList({})
class PublicationWorkflowRenameListenerTest
{
    // "manually mocked: yet
    private XWikiContext context;

    @RegisterExtension
    LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private PublicationWorkflowRenameListener renameListener;

    private DocumentReference oldReference;
    private DocumentReference newReference;
    private DocumentReference oldEquivalentReference;
    private DocumentReference expectedEquivReference;

    @BeforeEach
    public void setUp()
    {
        context = new XWikiContext();
        context.setWikiId("xwiki");
    }

    @Test
    void testSimpleRename()
    {
        oldReference = toRef(Arrays.asList("Drafts", "BeforeRename"), "WebHome");
        newReference = toRef(Arrays.asList("Drafts", "AfterRename"), "WebHome");
        oldEquivalentReference = toRef(Arrays.asList("Public", "BeforeRename"), "WebHome");
        expectedEquivReference = toRef(Arrays.asList("Public", "AfterRename"), "WebHome");
        check("simple rename");
    }

    @Test
    void testFromTerminalPage()
    {
        oldReference = toRef(Arrays.asList("Drafts"), "SimplePage");
        newReference = toRef(Arrays.asList("Drafts", "SimplePage"), "WebHome");
        oldEquivalentReference = toRef(Arrays.asList("Public"), "SimplePage");
        expectedEquivReference = toRef(Arrays.asList("Public", "SimplePage"), "WebHome");
        check("move from terminal page");
    }

    @Test
    void testToTerminalPage()
    {
        oldReference = toRef(Arrays.asList("Drafts", "SimplePage"), "WebHome");
        newReference = toRef(Arrays.asList("Drafts"), "SimplePage");
        oldEquivalentReference = toRef(Arrays.asList("Public", "SimplePage"), "WebHome");
        expectedEquivReference = toRef(Arrays.asList("Public"), "SimplePage");
        check("move to terminal page");
    }

    @Test
    void testMergeDifferentPathDepth()
    {
        oldReference = toRef(Arrays.asList("Drafts", "BeforeRename"), "WebHome");
        newReference = toRef(Arrays.asList("Drafts", "AfterRename"), "WebHome");
        oldEquivalentReference = toRef(Arrays.asList("Main", "Public", "BeforeRename"), "WebHome");
        expectedEquivReference = toRef(Arrays.asList("Main", "Public", "AfterRename"), "WebHome");
        check("different nesting merge");
    }

    @Test
    void testMoveDownThePath()
    {
        oldReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "Subpage1", "SubSubPage1-1"), "WebHome");
        newReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "Subpage1", "SubSubPage1-2", "SubSubPage1-2-1"), "WebHome");
        oldEquivalentReference = toRef(Arrays.asList("Main", "Public", "Topic", "Subpage1", "SubSubPage1-1"), "WebHome");
        expectedEquivReference = toRef(Arrays.asList("Main", "Public", "Topic", "Subpage1", "SubSubPage1-2", "SubSubPage1-2-1"), "WebHome");
        check("move down another sub tree");
    }

    @Test
    void testMoveUpThePath()
    {
        oldReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "SubpageA", "SubSubPageA-B", "SubSubPageA-B-C"), "WebHome");
        newReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "SubpageA", "SubSubPageD", "SubSubPageA-B-C"), "WebHome");
        oldEquivalentReference = toRef(Arrays.asList("Main", "Public", "Topic", "SubpageA", "SubSubPageA-B", "SubSubPageA-B-C"), "WebHome");
        expectedEquivReference = toRef(Arrays.asList("Main", "Public", "Topic", "SubpageA", "SubSubPageD", "SubSubPageA-B-C"), "WebHome");
        check("move up another sub tree");
    }

    @Test
    void testMoveUpThePathAndRename()
    {
        oldReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "SubpageA", "SubSubPageA-B", "SubSubPageA-B-C"), "WebHome");
        newReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "SubpageA", "SubSubPageA-D", "SubSubPageA-D-C"), "WebHome");
        oldEquivalentReference = toRef(Arrays.asList("Main", "Public", "Topic", "SubpageA", "SubSubPageA-B", "SubSubPageA-B-C"), "WebHome");
        expectedEquivReference = toRef(Arrays.asList("Main", "Public", "Topic", "SubpageA", "SubSubPageA-D", "SubSubPageA-D-C"), "WebHome");
        check("move up another sub tree and rename");
    }

    @Test
    void testMoveLocation()
    {
        oldReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "SubPageA1", "SubSubPageB1"), "WebHome");
        newReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "SubPageA2", "SubSubPageB1"), "WebHome");
        oldEquivalentReference = toRef(Arrays.asList("Public", "Topic", "SubPageA1", "SubSubPageB1"), "WebHome");
        expectedEquivReference = toRef(Arrays.asList("Public", "Topic", "SubPageA2", "SubSubPageB1"), "WebHome");
        check("move around in the tree");
    }

    // what happens if the situation before the rename was inconsistent?
    // the result is somewhat undefined; we try to "repair" the mismatch as good as possible
    // however if this causes problems with other cases, feel free to change or disable this test
    @Test
    void testFromBrokenLocation()
    {
        oldReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "SubPageA1", "SubSubPageB1"), "WebHome");
        newReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "SubPageA2", "SubSubPageB1"), "WebHome");
        oldEquivalentReference = toRef(Arrays.asList("Public", "Topic", "SubPageA3", "SubSubPageB1"), "WebHome");
        expectedEquivReference = toRef(Arrays.asList("Public", "Topic", "SubPageA2", "SubSubPageB1"), "WebHome");
        check("move around in the inconsistent tree");
    }


    // then there is a edge case where due to an inconsistent location
    // the equivalent page might be moved outside the expected area
    // this currently cannot be prevented
    @Test
    void testFromBrokenLocationUpThePath()
    {
        oldReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "TopicA1", "SubTopicB1"), "WebHome");
        newReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "TopicB1"), "WebHome");
        oldEquivalentReference = toRef(Arrays.asList("Public", "Topics", "TopicC"), "WebHome");
        expectedEquivReference = toRef(Arrays.asList("Public", "TopicB1"), "WebHome");
        // preferable we should have
        // expectedEquivReference = toRef(Arrays.asList("Public", "Topics", "TopicB1"), "WebHome");
        check("move up in the inconsistent tree");
    }

    // this case is even more dubious: should we create top level pages ?
    @Test
    void testFromBrokenLocationUpOutsideTheRoot()
    {
        oldReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "TopicA1", "SubTopicB1", "SubSubTopicC1"), "WebHome");
        newReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "TopicC1"), "WebHome");
        oldEquivalentReference = toRef(Arrays.asList("Topics", "SubSubTopicC1"), "WebHome");
        expectedEquivReference = toRef(Arrays.asList("TopicC1"), "WebHome");
        check("failed to move up in the inconsistent tree");
    }

    // here we definitely fail to get a new location, and keep the old one
    @Test
    void testTerminelPageFromBrokenLocatioUpOutsideTheRoot()
    {
        oldReference = toRef(Arrays.asList("Sandbox", "TopicDrafts", "TopicA1", "SubTopicB1"), "SubSubTopicC1");
        newReference = toRef(Arrays.asList("Sandbox", "TopicDrafts"), "TopicC1");
        oldEquivalentReference = toRef(Arrays.asList("Topics"), "SubSubTopicC1");
        expectedEquivReference = toRef(Arrays.asList("Topics"), "SubSubTopicC1");
        // maybe marginally better would be:
        // expectedEquivReference = toRef(Arrays.asList("Topics"), "TopicC1");
        check("failed to move up terminal page in the inconsistent tree");

        assertEquals("Unable to compute new location for the document [xwiki:Topics.SubSubTopicC1]",
            logCapture.getMessage(0));
    }


    private void check(String message)
    {
        DocumentReference newEqRef = renameListener.computeEquivalentDocRef(oldReference, newReference, oldEquivalentReference, context);
        assertEquals(expectedEquivReference, newEqRef, message);
    }

    private DocumentReference toRef(List<String> spaces, String pageName)
    {
        return new DocumentReference(context.getWikiId(), spaces, pageName);
    }
}
