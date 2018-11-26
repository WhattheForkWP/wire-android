/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.conversation

import android.content.Context
import android.os.Bundle
import android.support.annotation.Nullable
import android.support.v4.view.ViewPager
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.view.View.OnClickListener
import android.view.{LayoutInflater, View, ViewGroup}
import com.waz.ZLog.ImplicitTag.implicitLogTag
import com.waz.model.{Liking, RemoteInstant, UserId}
import com.waz.service.ZMessaging
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.events.{RefreshingSignal, Signal}
import com.waz.utils.returning
import com.waz.zclient.common.controllers.{ScreenController, ThemeController}
import com.waz.zclient.pages.main.conversation.ConversationManagerFragment
import com.waz.zclient.participants.ParticipantsAdapter
import com.waz.zclient.ui.text.{GlyphTextView, TypefaceTextView}
import com.waz.zclient.ui.views.tab.TabIndicatorLayout
import com.waz.zclient.utils.ContextUtils.getColorWithTheme
import com.waz.zclient.utils.{DateConvertUtils, ZTimeFormatter}
import com.waz.zclient.{FragmentHelper, R}
import org.threeten.bp.{LocalDateTime, ZoneId}
import com.waz.zclient.utils.RichView

import scala.collection.JavaConverters._

class LikesAndReadsFragment extends FragmentHelper {
  import Threading.Implicits.Ui
  implicit def ctx: Context = getActivity

  import LikesAndReadsFragment._

  private lazy val zms              = inject[Signal[ZMessaging]]
  private lazy val viewPager        = view[ViewPager](R.id.likes_and_reads_viewpager)
  private lazy val recyclerView     = view[RecyclerView](R.id.likes_recycler_view)
  private lazy val closeButton      = view[GlyphTextView](R.id.likes_close_button)

  private lazy val themeController  = inject[ThemeController]
  private lazy val screenController = inject[ScreenController]

  private lazy val likes: Signal[Seq[UserId]] = Signal(zms, screenController.showMessageDetails).flatMap {
    case (z, Some(msgId)) =>
      new RefreshingSignal[Seq[UserId], Seq[Liking]](
        CancellableFuture.lift(z.reactionsStorage.getLikes(msgId).map(_.likers.keys.toSeq)),
        z.reactionsStorage.onChanged.map(_.filter(_.message == msgId))
      )
    case _ => Signal.const(Seq.empty[UserId])
  }

  private lazy val reads: Signal[Seq[UserId]] = Signal(zms, screenController.showMessageDetails).flatMap {
    case (z, Some(msgId)) => Signal.const(Seq.empty[UserId])
    case _                => Signal.const(Seq.empty[UserId])
  }

  private lazy val message =
    for {
      z           <- zms
      Some(msgId) <- screenController.showMessageDetails
      msg         <- z.messagesStorage.signal(msgId)
    } yield msg

  private lazy val isOwnMessage =
    for {
      selfUserId  <- inject[Signal[UserId]]
      msg         <- message
    } yield selfUserId == msg.userId

  private lazy val title = returning(view[TypefaceTextView](R.id.message_details_title)) { vh =>
    isOwnMessage.map {
      case true  => R.string.message_details_title
      case false => R.string.message_likes_title
    }.onUi(resId => vh.foreach(_.setText(resId)))
  }

  private lazy val timestamp = returning(view[TypefaceTextView](R.id.message_timestamp)) { vh =>
    message.onUi { msg =>
      val ts = ZTimeFormatter.getSeparatorTime(getContext, LocalDateTime.now, DateConvertUtils.asLocalDateTime(msg.time.instant), true, ZoneId.systemDefault, true)
      val editTs = ZTimeFormatter.getSeparatorTime(getContext, LocalDateTime.now, DateConvertUtils.asLocalDateTime(msg.editTime.instant), true, ZoneId.systemDefault, true)
      val text =
        s"${getString(R.string.message_details_sent)}: $ts" +
          (if (msg.editTime != RemoteInstant.Epoch) s" • ${getString(R.string.message_details_last_edited)}: $editTs" else "")
      vh.foreach(_.setText(text))
    }
  }

  override def onCreateView(inflater: LayoutInflater, viewGroup: ViewGroup, savedInstanceState: Bundle): View =
    inflater.inflate(R.layout.fragment_likes_and_reads, viewGroup, false)

  private lazy val tabIndicatorLayout = returning(view[TabIndicatorLayout](R.id.likes_and_reads_tabs)) { vh =>
    // setting tabs' labels
    // this would be usually done in the adapter if the labels were constant
    // here we want the number of entries to be updated in real time
    Signal(reads.map(_.size), likes.map(_.size)).onUi { case (r, l) =>
      val readsLabel = s"${getString(R.string.tab_title_seen)} ($r)"
      val likesLabel = s"${getString(R.string.tab_title_likes)} ($l)"
      vh.foreach(_.setLabels(List(readsLabel, likesLabel).asJava))
    }
  }

  override def onViewCreated(v: View, @Nullable savedInstanceState: Bundle): Unit = {
    super.onViewCreated(v, savedInstanceState)

    Signal(screenController.showMessageDetails, isOwnMessage).head.foreach {
      case (Some(msgId), true) =>
        tabIndicatorLayout.foreach(_.setVisible(true))
        viewPager.foreach(_.setVisible(true))

        viewPager.foreach { pager =>
          pager.setVisible(true)
          pager.setAdapter(new TabbedLikesAndReadsPagerAdapter(msgId, likes, reads))
          tabIndicatorLayout.foreach { til =>
            til.setPrimaryColor(getColorWithTheme(if (themeController.isDarkTheme) R.color.text__secondary_dark else R.color.text__secondary_light))
            til.setViewPager(pager)
          }
        }

        if (Option(savedInstanceState).isEmpty) viewPager.foreach(
          _.setCurrentItem(getStringArg(ArgPageToOpen) match {
            case Some(TagLikes) => 1
            case _              => 0
          })
        )

      case _ =>
        recyclerView.foreach { rv =>
          rv.setVisible(true)
          rv.setLayoutManager(new LinearLayoutManager(getContext))
          rv.setAdapter(new ParticipantsAdapter(likes, showPeopleOnly = true, showArrow = false))
        }
    }

    closeButton.foreach(_.setOnClickListener(new OnClickListener {
      def onClick(v: View): Unit = onBackPressed()
    }))

    title
    timestamp
  }

  override def onBackPressed(): Boolean = Option(getParentFragment) match {
    case Some(f: ConversationManagerFragment) =>
      screenController.showMessageDetails ! None
      true
    case _ => false
  }
}

object LikesAndReadsFragment {
  val Tag = implicitLogTag
  val TagLikes: String = s"${classOf[LikesAndReadsFragment].getName}/likes"

  private val ArgPageToOpen: String = "ARG_PAGE_TO_OPEN"

  def newInstance(pageToOpen: Option[String] = None): LikesAndReadsFragment =
    returning(new LikesAndReadsFragment) { f =>
      pageToOpen.foreach { p =>
        f.setArguments(returning(new Bundle){
          _.putString(ArgPageToOpen, p)
        })
      }
    }
}
