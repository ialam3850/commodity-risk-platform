// Python bindings: `import pricing_core` from the research code.
//
//   >>> import pricing_core as pc
//   >>> pc.black76_price("call", F=3.2, K=3.5, T=0.25, r=0.04, vol=0.6)
//   >>> pc.implied_vol(0.12, "put", F=3.2, K=3.0, T=0.25, r=0.04)

#include <pybind11/pybind11.h>
#include <pybind11/stl.h>

#include <stdexcept>
#include <string>

#include "pricing/black76.h"
#include "pricing/var.h"

namespace py = pybind11;
using namespace pricing;

namespace {

OptionType parse_type(const std::string& s) {
  if (s == "call" || s == "C" || s == "c") return OptionType::Call;
  if (s == "put" || s == "P" || s == "p") return OptionType::Put;
  throw std::invalid_argument("option type must be 'call' or 'put', got '" + s + "'");
}

}  // namespace

PYBIND11_MODULE(pricing_core, m) {
  m.doc() = "Black-76 pricing, Greeks, implied volatility and VaR for futures and options on futures";

  py::enum_<OptionType>(m, "OptionType").value("Call", OptionType::Call).value("Put", OptionType::Put);

  py::class_<Greeks>(m, "Greeks")
      .def_readonly("price", &Greeks::price)
      .def_readonly("delta", &Greeks::delta)
      .def_readonly("gamma", &Greeks::gamma)
      .def_readonly("vega", &Greeks::vega)
      .def_readonly("theta", &Greeks::theta)
      .def_readonly("rho", &Greeks::rho)
      .def("__repr__", [](const Greeks& g) {
        return "Greeks(price=" + std::to_string(g.price) + ", delta=" + std::to_string(g.delta) +
               ", gamma=" + std::to_string(g.gamma) + ", vega=" + std::to_string(g.vega) +
               ", theta=" + std::to_string(g.theta) + ", rho=" + std::to_string(g.rho) + ")";
      });

  m.def(
      "black76_price",
      [](const std::string& type, double F, double K, double T, double r, double vol) {
        return black76_price({parse_type(type), F, K, T, r, vol});
      },
      py::arg("type"), py::arg("F"), py::arg("K"), py::arg("T"), py::arg("r"), py::arg("vol"));

  m.def(
      "black76_greeks",
      [](const std::string& type, double F, double K, double T, double r, double vol) {
        return black76_greeks({parse_type(type), F, K, T, r, vol});
      },
      py::arg("type"), py::arg("F"), py::arg("K"), py::arg("T"), py::arg("r"), py::arg("vol"));

  m.def(
      "implied_vol",
      [](double price, const std::string& type, double F, double K, double T, double r) {
        return implied_vol(price, parse_type(type), F, K, T, r);
      },
      py::arg("price"), py::arg("type"), py::arg("F"), py::arg("K"), py::arg("T"), py::arg("r"),
      "Implied volatility, or None if the price is outside the no-arbitrage bounds.");

  py::class_<Position> pos(m, "Position");
  py::enum_<Position::Kind>(pos, "Kind")
      .value("Future", Position::Kind::Future)
      .value("Option", Position::Kind::Option);
  pos.def(py::init<>())
      .def_static(
          "future",
          [](std::size_t factor, double qty, double mult) {
            Position p;
            p.factor = factor;
            p.quantity = qty;
            p.multiplier = mult;
            return p;
          },
          py::arg("factor"), py::arg("quantity"), py::arg("multiplier"))
      .def_static(
          "option",
          [](std::size_t factor, double qty, double mult, const std::string& type, double K, double T,
             double vol, double r) {
            Position p;
            p.kind = Position::Kind::Option;
            p.factor = factor;
            p.quantity = qty;
            p.multiplier = mult;
            p.type = parse_type(type);
            p.strike = K;
            p.expiry = T;
            p.vol = vol;
            p.rate = r;
            return p;
          },
          py::arg("factor"), py::arg("quantity"), py::arg("multiplier"), py::arg("type"), py::arg("K"),
          py::arg("T"), py::arg("vol"), py::arg("r"))
      .def_readwrite("kind", &Position::kind)
      .def_readwrite("factor", &Position::factor)
      .def_readwrite("quantity", &Position::quantity)
      .def_readwrite("multiplier", &Position::multiplier)
      .def_readwrite("type", &Position::type)
      .def_readwrite("strike", &Position::strike)
      .def_readwrite("expiry", &Position::expiry)
      .def_readwrite("vol", &Position::vol)
      .def_readwrite("rate", &Position::rate);

  py::class_<Portfolio>(m, "Portfolio")
      .def(py::init([](std::vector<double> prices, std::vector<Position> positions) {
             return Portfolio{std::move(prices), std::move(positions)};
           }),
           py::arg("prices"), py::arg("positions"))
      .def_readwrite("prices", &Portfolio::prices)
      .def_readwrite("positions", &Portfolio::positions)
      .def("value", &Portfolio::value, py::arg("factor_prices"), py::arg("elapsed") = 0.0);

  py::class_<VarResult>(m, "VarResult")
      .def_readonly("var", &VarResult::var)
      .def_readonly("es", &VarResult::es)
      .def_readonly("scenarios", &VarResult::scenarios)
      .def_readonly("tail", &VarResult::tail)
      .def("__repr__", [](const VarResult& r) {
        return "VarResult(var=" + std::to_string(r.var) + ", es=" + std::to_string(r.es) +
               ", scenarios=" + std::to_string(r.scenarios) + ", tail=" + std::to_string(r.tail) + ")";
      });

  m.def("historical_var", &historical_var, py::arg("portfolio"), py::arg("log_returns"),
        py::arg("confidence"), py::arg("horizon_days") = 1, py::call_guard<py::gil_scoped_release>());

  m.def(
      "monte_carlo_var",
      [](const Portfolio& p, const std::vector<double>& vols, const std::vector<std::vector<double>>& corr,
         double confidence, std::size_t paths, int horizon_days, std::uint64_t seed, int threads) {
        std::vector<double> flat;
        for (const auto& row : corr) {
          if (row.size() != corr.size()) throw std::invalid_argument("correlation must be square");
          flat.insert(flat.end(), row.begin(), row.end());
        }
        MonteCarloConfig cfg;
        cfg.paths = paths;
        cfg.horizon_days = horizon_days;
        cfg.seed = seed;
        cfg.threads = threads;
        py::gil_scoped_release release;  // the threads don't touch Python objects
        return monte_carlo_var(p, vols, flat, confidence, cfg);
      },
      py::arg("portfolio"), py::arg("vols"), py::arg("correlation"), py::arg("confidence"),
      py::arg("paths") = 100'000, py::arg("horizon_days") = 1, py::arg("seed") = 42, py::arg("threads") = 0);
}
