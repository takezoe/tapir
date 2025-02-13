package sttp.tapir.server

import java.nio.charset.Charset

import com.github.ghik.silencer.silent
import com.twitter.finagle.http.{Method, Request, Response, Status}
import com.twitter.inject.Logging
import com.twitter.util.Future
import sttp.tapir.EndpointInput.{FixedMethod, PathCapture}
import sttp.tapir.internal.server.{DecodeInputs, DecodeInputsResult, InputValues}
import sttp.tapir.internal.{SeqToParams, _}
import sttp.tapir.{DecodeFailure, DecodeResult, Endpoint, EndpointIO, EndpointInput}

import scala.reflect.ClassTag
import scala.util.control.NonFatal

package object finatra {
  implicit class RichFinatraEndpoint[I, E, O](e: Endpoint[I, E, O, Nothing]) extends Logging {
    private def httpMethod(endpoint: Endpoint[I, E, O, Nothing]): Method = {
      endpoint.input
        .asVectorOfBasicInputs()
        .collectFirst {
          case FixedMethod(m) => Method(m.method)
        }
        .getOrElse(Method("ANY"))
    }

    def toRoute(logic: I => Future[Either[E, O]])(implicit serverOptions: FinatraServerOptions): FinatraRoute = {
      val handler = { request: Request =>
        def decodeBody(result: DecodeInputsResult): Future[DecodeInputsResult] = {
          result match {
            case values: DecodeInputsResult.Values =>
              values.bodyInput match {
                case Some(bodyInput @ EndpointIO.Body(codec, _)) =>
                  new FinatraRequestToRawBody(serverOptions)
                    .apply(codec.meta.rawValueType, request.content, request.charset.map(Charset.forName), request)
                    .map { rawBody =>
                      val decodeResult = codec.decode(DecodeInputs.rawBodyValueToOption(rawBody, codec.meta.schema.isOptional))
                      decodeResult match {
                        case DecodeResult.Value(bodyV) => values.setBodyInputValue(bodyV)
                        case failure: DecodeFailure    => DecodeInputsResult.Failure(bodyInput, failure): DecodeInputsResult
                      }
                    }
                case None => Future.value(values)
              }
            case failure: DecodeInputsResult.Failure => Future.value(failure)
          }
        }

        def valuesToResponse(values: DecodeInputsResult.Values): Future[Response] = {
          val i = SeqToParams(InputValues(e.input, values)).asInstanceOf[I]
          logic(i)
            .map {
              case Right(result) => OutputToFinatraResponse(Status(ServerDefaults.StatusCodes.success.code), e.output, result)
              case Left(err)     => OutputToFinatraResponse(Status(ServerDefaults.StatusCodes.error.code), e.errorOutput, err)
            }
            .onFailure {
              case NonFatal(ex) =>
                error(ex)
            }
        }

        def handleDecodeFailure(
            e: Endpoint[_, _, _, _],
            req: Request,
            input: EndpointInput.Single[_],
            failure: DecodeFailure
        ): Response = {
          val handling = serverOptions.decodeFailureHandler(DecodeFailureContext(req, input, failure))

          handling match {
            case DecodeFailureHandling.NoMatch =>
              serverOptions.loggingOptions.decodeFailureNotHandledMsg(e, failure, input).foreach(debug(_))
              Response(Status.BadRequest)
            case DecodeFailureHandling.RespondWithResponse(output, value) =>
              serverOptions.loggingOptions.decodeFailureHandledMsg(e, failure, input, value).foreach {
                case (msg, Some(t)) => debug(msg, t)
                case (msg, None)    => debug(msg)
              }

              OutputToFinatraResponse(Status(ServerDefaults.StatusCodes.error.code), output, value)
          }
        }

        decodeBody(DecodeInputs(e.input, new FinatraDecodeInputsContext(request))).flatMap {
          case values: DecodeInputsResult.Values          => valuesToResponse(values)
          case DecodeInputsResult.Failure(input, failure) => Future.value(handleDecodeFailure(e, request, input, failure))
        }
      }

      FinatraRoute(handler, httpMethod(e), e.input.path)
    }

    @silent("never used")
    def toRouteRecoverErrors(logic: I => Future[O])(
        implicit eIsThrowable: E <:< Throwable,
        eClassTag: ClassTag[E]
    ): FinatraRoute = {
      e.toRoute { i: I =>
        logic(i).map(Right(_)).handle {
          case ex if eClassTag.runtimeClass.isInstance(ex) => Left(ex.asInstanceOf[E])
        }
      }
    }
  }

  implicit class SuperRichEndpointInput[I](input: EndpointInput[I]) {
    def path: String = {
      val p = input
        .asVectorOfBasicInputs()
        .collect {
          case segment: EndpointInput.FixedPath => segment.show
          case PathCapture(_, Some(name), _)    => s"/:$name"
          case PathCapture(_, _, _)             => "/:param"
          case EndpointInput.PathsCapture(_)    => "/:*"
        }
        .mkString
      if (p.isEmpty) "/:*" else p
    }
  }
}
