# Commodity risk platform

A futures ledger, pricing core, and research pipeline for commodity risk.

## Available components

- `pricing-core/`: C++20 Black-76 prices, Greeks, implied volatility, and tests.

## Run the checks

Run these commands from the repository root.

C++ requires CMake 3.20+, Ninja, and a C++20 compiler.

```bash
cmake -S pricing-core -B pricing-core/build -G Ninja -DPC_BUILD_PYTHON=OFF
cmake --build pricing-core/build
ctest --test-dir pricing-core/build --output-on-failure
```
