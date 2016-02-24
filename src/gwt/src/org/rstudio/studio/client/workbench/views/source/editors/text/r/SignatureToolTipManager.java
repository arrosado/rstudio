/*
 * SignatureToolTipManager.java
 *
 * Copyright (C) 2009-15 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.source.editors.text.r;

import org.rstudio.core.client.Debug;
import org.rstudio.core.client.DocumentIdleBackgroundTask;
import org.rstudio.core.client.StringUtil;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.common.codetools.CodeToolsServerOperations;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.workbench.prefs.model.UIPrefs;
import org.rstudio.studio.client.workbench.views.source.editors.text.AceEditor;
import org.rstudio.studio.client.workbench.views.source.editors.text.DocDisplay;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Position;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Range;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.TokenCursor;

import com.google.gwt.dom.client.NativeEvent;
import com.google.inject.Inject;

public class SignatureToolTipManager
{
   public SignatureToolTipManager(DocDisplay docDisplay)
   {
      RStudioGinjector.INSTANCE.injectMembers(this);
      toolTip_ = new RCompletionToolTip(docDisplay);
      docDisplay_ = docDisplay;
      idleTask_ = new DocumentIdleBackgroundTask(
            docDisplay,
            new DocumentIdleBackgroundTask.Command()
            {
               @Override
               public boolean onIdle(Position position, boolean isMouse)
               {
                  resolveActiveFunctionAndDisplayToolTip(position, !isMouse);
                  return true;
               }
            });
   }
   
   @Inject
   public void initialize(UIPrefs uiPrefs,
                          CodeToolsServerOperations server)
   {
      uiPrefs_ = uiPrefs;
      server_ = server;
   }
   
   public RCompletionToolTip getToolTip()
   {
      return toolTip_;
   }
   
   public boolean previewKeyDown(NativeEvent event)
   {
      return toolTip_.previewKeyDown(event);
   }
   
   private final native boolean isBoringFunction(String name) /*-{
      var boring = ["c", "list"];
      return boring.some(function(x) { return x === name; });
   }-*/;
   
   public void displayToolTip(final String name, final String source)
   {
      if (isBoringFunction(name))
         return;
      
      server_.getArgs(name, source, new ServerRequestCallback<String>()
      {
         @Override
         public void onResponseReceived(String response)
         {
            if (StringUtil.isNullOrEmpty(response))
               return;
            
            toolTip_.resolvePositionAndShow(name + response);
         }

         @Override
         public void onError(ServerError error)
         {
            Debug.logError(error);
         }
      });
   }
   
   public void resolveActiveFunctionAndDisplayToolTip(
         final boolean searchForFunction)
   {
      resolveActiveFunctionAndDisplayToolTip(
            docDisplay_.getCursorPosition(),
            searchForFunction);
   }
   
   public void resolveActiveFunctionAndDisplayToolTip(
         final Position position,
         final boolean searchForFunction)
   {
            
      if (!uiPrefs_.showSignatureTooltips().getValue())
         return;
      
      if (docDisplay_.isPopupVisible())
         return;
      
      AceEditor editor = (AceEditor) docDisplay_;
      if (editor == null)
         return;
      
      TokenCursor cursor = editor.getSession().getMode().getRCodeModel().getTokenCursor();
      if (!cursor.moveToPosition(position))
         return;

      if (searchForFunction && !cursor.moveToActiveFunction())
         return;
      
      if (!cursor.nextValue().equals("("))
         return;
      
      final TokenCursor callEndCursor = cursor.cloneCursor();
      Position callEndPos = callEndCursor.currentPosition();
      callEndPos.setColumn(
            callEndPos.getColumn() +
            callEndCursor.currentValue().length());
      
      if (!cursor.findStartOfEvaluationContext())
         return;

      final String callString = editor.getTextForRange(Range.fromPoints(
            cursor.currentPosition(),
            callEndPos));
      
      if (isBoringFunction(callString))
         return;

      server_.getArgs(callString, "", new ServerRequestCallback<String>() {
         
         @Override
         public void onResponseReceived(String arguments)
         {
            final String signature = callString + arguments;
            
            if (StringUtil.isNullOrEmpty(arguments))
               return;
            
            if (toolTip_.isShowing() && toolTip_.getSignature().equals(signature))
               return;
            
            if (!searchForFunction)
            {
               toolTip_.resolvePositionAndShow(
                     signature,
                     position);
            }
            else
            {
               TokenCursor endCursor = callEndCursor.cloneCursor();
               if (!endCursor.moveToNextToken())
                  return;
               
               if (!endCursor.fwdToMatchingToken())
                  return;
               
               toolTip_.resolvePositionAndShow(
                     signature,
                     Range.fromPoints(
                           callEndCursor.currentPosition(),
                           endCursor.currentPosition()));
            }
         }

         @Override
         public void onError(ServerError error)
         {
            Debug.logError(error);
         }
      });
   }
   
   private final RCompletionToolTip toolTip_;
   private final DocDisplay docDisplay_;
   private final DocumentIdleBackgroundTask idleTask_;
   private Position lastCursorPosition_;

   private UIPrefs uiPrefs_;
   private CodeToolsServerOperations server_;
   
   private static final int DELAY_MS = 1200;
}
