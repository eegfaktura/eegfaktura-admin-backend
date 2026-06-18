package at.ourproject.routes

import org.apache.pekko.http.scaladsl.server.Route

trait Router {
  def route: Route
}
