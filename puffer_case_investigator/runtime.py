"""Balanced paired-runtime scheduling and deterministic bootstrap statistics."""

from __future__ import annotations

import math
import random
import statistics
from typing import Iterable, List, Sequence, Tuple

from .models import PairedRuntimeStats, RuntimeSeriesSummary


DEFAULT_BOOTSTRAP_ITERATIONS = 10_000
DEFAULT_BOOTSTRAP_SEED = 0xC0_99_E2


def paired_execution_order(rounds: int = 9) -> Tuple[Tuple[str, str], ...]:
    if rounds <= 0:
        raise ValueError("rounds must be positive")
    return tuple(
        ("default", "counterfactual") if index % 2 == 0 else ("counterfactual", "default")
        for index in range(rounds)
    )


def summarize_paired_runtimes(
    default_samples_ms: Sequence[float] | Iterable[float],
    counterfactual_samples_ms: Sequence[float] | Iterable[float],
    *,
    confidence: float = 0.95,
    bootstrap_iterations: int = DEFAULT_BOOTSTRAP_ITERATIONS,
    bootstrap_seed: int = DEFAULT_BOOTSTRAP_SEED,
) -> PairedRuntimeStats:
    default = _validated_samples(default_samples_ms, "default")
    counterfactual = _validated_samples(counterfactual_samples_ms, "counterfactual")
    if len(default) != len(counterfactual):
        raise ValueError("paired runtime series must have the same number of samples")
    if not default:
        raise ValueError("paired runtime series must not be empty")
    if not 0.0 < confidence < 1.0:
        raise ValueError("confidence must be between 0 and 1")
    if bootstrap_iterations <= 0:
        raise ValueError("bootstrap_iterations must be positive")

    default_summary = _summarize(default)
    counterfactual_summary = _summarize(counterfactual)
    median_speedup = default_summary.median_ms / counterfactual_summary.median_ms
    bootstrap = _bootstrap_speedups(
        default,
        counterfactual,
        bootstrap_iterations,
        bootstrap_seed,
    )
    # A "95% lower bound" is one-sided, hence the fifth percentile rather
    # than the lower edge of a two-sided 95% interval.
    lower_quantile = 1.0 - confidence
    lower_bound = _percentile(bootstrap, lower_quantile)

    return PairedRuntimeStats(
        default=default_summary,
        counterfactual=counterfactual_summary,
        pair_count=len(default),
        counterfactual_wins=sum(left > right for left, right in zip(default, counterfactual)),
        median_speedup=median_speedup,
        median_absolute_gap_ms=abs(
            default_summary.median_ms - counterfactual_summary.median_ms
        ),
        bootstrap_confidence=confidence,
        bootstrap_speedup_lower_bound=lower_bound,
        bootstrap_iterations=bootstrap_iterations,
        bootstrap_seed=bootstrap_seed,
    )


def _validated_samples(values: Iterable[float], name: str) -> Tuple[float, ...]:
    parsed: List[float] = []
    for index, value in enumerate(values):
        try:
            number = float(value)
        except (TypeError, ValueError) as error:
            raise ValueError(f"{name} runtime sample {index} is not numeric") from error
        if not math.isfinite(number) or number <= 0.0:
            raise ValueError(f"{name} runtime sample {index} must be finite and positive")
        parsed.append(number)
    return tuple(parsed)


def _summarize(samples: Tuple[float, ...]) -> RuntimeSeriesSummary:
    return RuntimeSeriesSummary(
        samples_ms=samples,
        median_ms=statistics.median(samples),
        mean_ms=statistics.fmean(samples),
        minimum_ms=min(samples),
        maximum_ms=max(samples),
    )


def _bootstrap_speedups(
    default: Tuple[float, ...],
    counterfactual: Tuple[float, ...],
    iterations: int,
    seed: int,
) -> List[float]:
    generator = random.Random(seed)
    count = len(default)
    results: List[float] = []
    for _ in range(iterations):
        indices = [generator.randrange(count) for _ in range(count)]
        default_median = statistics.median(default[index] for index in indices)
        counterfactual_median = statistics.median(counterfactual[index] for index in indices)
        results.append(default_median / counterfactual_median)
    results.sort()
    return results


def _percentile(sorted_values: Sequence[float], quantile: float) -> float:
    if not sorted_values:
        raise ValueError("cannot calculate a percentile of an empty series")
    position = quantile * (len(sorted_values) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    fraction = position - lower
    return sorted_values[lower] * (1.0 - fraction) + sorted_values[upper] * fraction


__all__ = [
    "DEFAULT_BOOTSTRAP_ITERATIONS",
    "DEFAULT_BOOTSTRAP_SEED",
    "paired_execution_order",
    "summarize_paired_runtimes",
]
