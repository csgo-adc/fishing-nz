import Link from "next/link";

const features = [
  { eyebrow: "Plan", title: "Find the right window", description: "Bring weather, tides, wind, and daylight into one clear fishing forecast." },
  { eyebrow: "Explore", title: "Discover nearby spots", description: "Compare New Zealand fishing locations by access, conditions, and target species." },
  { eyebrow: "Check", title: "Know before you keep", description: "Identify a catch and check current regional size and bag limits before keeping it." },
];

export default function Home() {
  return (
    <main>
      <header className="site-header">
        <Link className="brand" href="/"><span className="brand-mark" aria-hidden="true">✓</span>CatchCheck NZ</Link>
        <nav aria-label="Main navigation">
          <a href="#features">Features</a>
          <a href="#conditions">Conditions</a>
          <Link href="/admin">Admin</Link>
        </nav>
      </header>

      <section className="hero">
        <div className="hero-copy">
          <p className="kicker">A fishing companion for Aotearoa</p>
          <h1>Make every fishing trip a better call.</h1>
          <p className="hero-description">Plan around local conditions, explore promising spots, identify your catch, and check the rules in one place.</p>
          <div className="hero-actions">
            <a className="button button-primary" href="#conditions">Check conditions</a>
            <a className="button button-secondary" href="#features">Explore features</a>
          </div>
        </div>
        <aside className="condition-card" id="conditions">
          <div className="condition-heading">
            <div><p>Today near you</p><h2>Good conditions</h2></div>
            <span className="score">8.2</span>
          </div>
          <dl className="metrics">
            <div><dt>Wind</dt><dd>Light</dd></div>
            <div><dt>Tide</dt><dd>Incoming</dd></div>
            <div><dt>Water</dt><dd>18°C</dd></div>
          </dl>
          <p className="demo-note">Sample preview · live data will come from the CatchCheck API.</p>
        </aside>
      </section>

      <section className="features" id="features">
        <div className="section-heading"><p className="kicker">One reliable view</p><h2>From planning to the catch</h2></div>
        <div className="feature-grid">
          {features.map((feature) => (
            <article key={feature.title} className="feature-card">
              <span>{feature.eyebrow}</span><h3>{feature.title}</h3><p>{feature.description}</p>
            </article>
          ))}
        </div>
      </section>

      <footer><p>CatchCheck NZ</p><p>Always verify safety conditions and official fishing rules before heading out.</p></footer>
    </main>
  );
}
