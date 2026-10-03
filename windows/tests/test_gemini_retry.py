from app.gemini_service import _model_order, _retryable, _model_missing


def test_model_order_keeps_primary_and_fallbacks_unique():
    order = _model_order('gemini-3.8-flash')
    assert order[0] == 'gemini-3.8-flash'
    assert len(order) == len(set(order))
    assert 'gemini-3.7-flash' in order
    assert 'gemini-3.6-flash' in order


def test_retry_and_model_missing_detection():
    assert _retryable(RuntimeError('503 UNAVAILABLE high demand'))
    assert _retryable(RuntimeError('429 RESOURCE_EXHAUSTED'))
    assert _model_missing(RuntimeError('404 NOT_FOUND model not found'))
