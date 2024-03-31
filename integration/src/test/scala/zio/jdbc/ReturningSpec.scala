package zio.jdbc

import zio.Scope
import zio.schema.{ Schema, TypeId }
import zio.test.Assertion._
import zio.test.TestAspect.after
import zio.test._

import java.sql.ResultSet
import java.util.UUID

object ReturningSpec extends PgSpec {
  final case class User(internalId: UUID, name: String, age: Int)
  object User {
    implicit val jdbcDecoder: JdbcDecoder[User] =
      JdbcDecoder[(UUID, String, Int)].map((User.apply _).tupled)
    implicit val jdbcEncoder: JdbcEncoder[User] =
      JdbcEncoder[(UUID, String, Int)].contramap(User.unapply(_).get)
  }

  final case class U(internalId: UUID, name: String, age: Int)
  object U {
    implicit val schema: Schema[U]           = Schema
      .CaseClass3[UUID, String, Int, U](
        id0 = TypeId.fromTypeName("U"),
        field01 = Schema.Field(
          name0 = "internalId",
          schema0 = Schema[UUID],
          get0 = _.internalId,
          set0 = (u, x) => u.copy(internalId = x)
        ),
        field02 =
          Schema.Field(name0 = "name", schema0 = Schema[String], get0 = _.name, set0 = (u, x) => u.copy(name = x)),
        field03 = Schema.Field(name0 = "age", schema0 = Schema[Int], get0 = _.age, set0 = (u, x) => u.copy(age = x)),
        construct0 = (ii, name, age) => U(ii, name, age)
      )
    implicit val jdbcDecoder: JdbcDecoder[U] = JdbcDecoder.fromSchema
    implicit val jdbcEncoder: JdbcEncoder[U] = JdbcEncoder.fromSchema
  }

  final case class InvalidDecoderUser(internalId: UUID, name: String, age: Int)
  object InvalidDecoderUser {
    implicit val jdbcDecoder: JdbcDecoder[InvalidDecoderUser] =
      new JdbcDecoder[InvalidDecoderUser] {
        override def unsafeDecode(columIndex: Int, rs: ResultSet): (Int, InvalidDecoderUser) =
          throw new RuntimeException("Boom!")
      }
    implicit val jdbcEncoder: JdbcEncoder[InvalidDecoderUser] =
      JdbcEncoder[(UUID, String, Int)].contramap(InvalidDecoderUser.unapply(_).get)
  }

  val genUser: Gen[Any, User] =
    for {
      uuid <- Gen.uuid
      name <- Gen.alphaNumericString.map(_.take(40))
      age  <- Gen.int(10, 100)
    } yield User(internalId = uuid, name = name, age = age)

  val genInvalidDecoderUser: Gen[Any, InvalidDecoderUser] =
    genUser.map(u => InvalidDecoderUser(internalId = u.internalId, name = u.name, age = u.age))

  val spec: Spec[ZConnectionPool with TestEnvironment with Scope, Any] =
    suite("Returning")(
      test("Inserts returning rows") {
        check(Gen.chunkOf1(genUser)) { users =>
          for {
            result <- transaction {
                        (sql"""INSERT INTO users(internalId, name, age)""".values(
                          users.toChunk
                        ) ++ " RETURNING id, internalId, name, age")
                          .insertReturning[(Int, UUID, String, Int)]
                      }
            _      <- transaction(sql"DELETE FROM users".delete)
          } yield assert(result.rowsUpdated)(Assertion.equalTo(users.size.toLong)) &&
            assert(result.updatedKeys.map(_._1).toSet)(Assertion.hasSameElements(result.updatedKeys.map(_._1)))
        }
      },
      test("Inserts returning rows - propagate decoding errors") {
        check(Gen.chunkOf1(genInvalidDecoderUser)) { users =>
          val insertResult =
            transaction {
              (sql"""INSERT INTO users(internalId, name, age)""".values(
                users.toChunk
              ) ++ " RETURNING internalId, name, age")
                .insertReturning[InvalidDecoderUser]
            }

          assertZIO(insertResult.exit)(fails(isSubtype[RuntimeException](hasMessage(equalTo("Boom!")))))
        }
      } @@ TestAspect.samples(1),
      test("Updates returning rows") {
        check(Gen.chunkOf1(genUser)) { users =>
          for {
            _      <- transaction {
                        sql"""INSERT INTO users(internalId, name, age)""".values(users.toChunk).execute
                      }
            result <- transaction {
                        sql"""UPDATE users SET age = age * 2 RETURNING internalId, name, age""".updateReturning[User]
                      }
            _      <- transaction(sql"DELETE FROM users".delete)
          } yield assert(result.rowsUpdated)(Assertion.equalTo(users.size.toLong)) &&
            assert(result.updatedKeys)(Assertion.hasSameElements(users.map(u => u.copy(age = u.age * 2))))
        }
      } @@ TestAspect.samples(2),
      test("Deletes returning rows") {
        check(Gen.chunkOf1(genUser)) { users =>
          for {
            _      <- transaction {
                        sql"""INSERT INTO users(internalId, name, age)""".values(users.toChunk).execute
                      }
            result <- transaction {
                        sql"""DELETE FROM users RETURNING internalId, name, age""".deleteReturning[User]
                      }
          } yield assert(result.rowsUpdated)(Assertion.equalTo(users.size.toLong)) &&
            assert(result.updatedKeys)(Assertion.hasSameElements(users))
        }
      },
      test("U") {
        val id   = UUID.fromString("7543dc39-98f8-44a6-aefc-bd90d9124e46")
        val name = "bob"
        val age  = 21
        for {
          _      <-
            transaction(
              sql"insert into u (internalId, name, age)".values(U(id, name, age)).insert
            )
          result <- transaction(sql"select internalId, name, age from u".query[(UUID, String, Int)].as[U].selectOne)
        } yield assertTrue(result.get == U(id, name, age))
      }
    ) @@ TestAspect.sequential @@ TestAspect.around(
      transaction(sql"CREATE TABLE users (id SERIAL, internalId UUID, name VARCHAR(40), age INTEGER)".execute) *>
        transaction(sql"CREATE TABLE u (id SERIAL, internalId UUID, name VARCHAR(40), age INTEGER)".execute),
      transaction(sql"DROP TABLE users".execute).orDie *>
        transaction(sql"DROP TABLE u".execute).orDie
    )
}
