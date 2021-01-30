package eu.spaziodati.metrics.streams


class StreamsLoader {

}

object StreamsLoader {
  def main(args: Array[String]): Unit = {
    val configPath = args(0)
    val streams = List[StartableStream](
      new kappaStream,
      new etaStream,
      new thetaStream,
      new lambdaStream,
    )
    var threads = List[Thread]()
    for (stream <- streams) {
      Runtime.getRuntime.addShutdownHook(stream.getShutdownHook)
      threads ::= new Thread(stream.name) {
          override def run(): Unit = {
            stream.start(configPath)
          }
        }
    }
    for (thread <- threads) {
      thread.start()
    }
    for (thread <- threads) {
      thread.join()
    }
  }
}
