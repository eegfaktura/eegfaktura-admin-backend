package at.ourproject

object Config {
  case class Auth(url: String, clientId: String, clientSecret: String, realm: String)
  case class Configuration(auth: Auth)

  def config(): Configuration = Configuration(
    Auth("https://login.ourproject.at/auth", "admin-cli", "qzCRMVWS6PnDwJ3v5JkZfWcTbBTZrBBU", "VFEEG"))
//    Auth("http://localhost:8180/auth", "admin-cli", "bgqHFY8RHf6e2oHGyXYSepJ0xnzMr5kr", "vfeeg"))
//    Auth("http://localhost:8180/auth", "realm-management", "QfNtbYYJdCwQ1vfCZpSi3792Vx7vTdoS", "vfeeg"))
}
