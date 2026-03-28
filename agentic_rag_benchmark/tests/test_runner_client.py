import unittest
from unittest import mock

from agentic_rag_benchmark.runner_client import BenchmarkAppClient
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

    def test_get_turn_summary_retries_404_until_summary_is_visible(self) -> None:
        client = BenchmarkAppClient(base_url="http://127.0.0.1:8081", timeout_seconds=3, verify_ssl=False)

        response_404 = mock.Mock()
        response_404.status_code = 404

        response_200 = mock.Mock()
        response_200.status_code = 200
        response_200.json.return_value = {"turnId": "turn-1", "finalAnswer": "ok"}

        with mock.patch("agentic_rag_benchmark.runner_client.requests.get", side_effect=[response_404, response_200]) as patched_get:
            with mock.patch("agentic_rag_benchmark.runner_client.time.sleep") as patched_sleep:
                summary = client.get_turn_summary("turn-1")

        self.assertEqual(summary["turnId"], "turn-1")
        self.assertEqual(patched_get.call_count, 2)
        patched_sleep.assert_called_once()


if __name__ == "__main__":
    unittest.main()
