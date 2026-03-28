import unittest

from agentic_rag_benchmark.runner_client import collect_agent_stream_capture


class RunnerClientTest(unittest.TestCase):
    def test_collect_agent_stream_capture_extracts_turn_id_and_event_counts(self) -> None:
        capture = collect_agent_stream_capture(
            [
                "event: turn_start",
                'data: {"type":"turn_start","turnId":"turn-1"}',
                "",
                'data: {"type":"delta","content":"hello"}',
                'data: {"type":"done","finishReason":"stop"}',
            ]
        )

        self.assertEqual(capture.turn_id, "turn-1")
        self.assertEqual(capture.event_count, 3)
        self.assertEqual(capture.event_type_counts["turn_start"], 1)
        self.assertEqual(capture.event_type_counts["delta"], 1)
        self.assertEqual(capture.event_type_counts["done"], 1)
        self.assertIsNone(capture.transport_error)

    def test_collect_agent_stream_capture_keeps_transport_error(self) -> None:
        capture = collect_agent_stream_capture(
            [
                'data: {"type":"turn_start","turnId":"turn-1"}',
                'data: {"type":"error","content":"provider failed"}',
            ]
        )

        self.assertEqual(capture.turn_id, "turn-1")
        self.assertEqual(capture.transport_error, "provider failed")
        self.assertEqual(capture.event_count, 2)


if __name__ == "__main__":
    unittest.main()
