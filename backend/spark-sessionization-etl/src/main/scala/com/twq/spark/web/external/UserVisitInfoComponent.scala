package com.twq.spark.web.external

import com.twq.spark.web.CombinedId

trait UserVisitInfoComponent {
  def retrieveUsersVisitInfo(ids: Seq[CombinedId]): Map[CombinedId, UserVisitInfo]

  def updateUsersVisitInfo(users: Seq[UserVisitInfo]): Unit
}
