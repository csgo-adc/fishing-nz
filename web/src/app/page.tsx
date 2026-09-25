import Link from "next/link";

const features = [
  {
    number: "01",
    label: "Find your window",
    title: "Go when it looks good",
    description: "Compare named fishing areas by weather, wind, tides, and daylight for the hours you want to head out.",
  },
  {
    number: "02",
    label: "Explore the coast",
    title: "Find a place that fits",
    description: "Browse land and boat spots, then narrow the search by how far you are willing to travel.",
  },
  {
    number: "03",
    label: "Check the details",
    title: "Know before you keep",
    description: "Review local fishing rules and tide predictions before you make your final call.",
  },
];

export default function Home() {
  return (
    <main>
      <header className="site-header">
        <Link className="brand" href="/" aria-label="CatchCheck NZ home">
          <span className="brand-mark" aria-hidden="true">✓</span>
          <span>CatchCheck <span className="brand-country">NZ</span></span>
        </Link>
        <nav className="site-nav" aria-label="Main navigation">
          <a href="#features">Features</a>
          <a href="#how-it-works">How it works</a>
          <Link className="nav-account" href="/account">Your account <span aria-hidden="true">↗</span></Link>
        </nav>
      </header>

      <section className="hero" aria-labelledby="hero-title">
        <div className="hero-copy">
          <p className="kicker">Your next trip starts here</p>
          <h1 id="hero-title">More time fishing.<br /><em>Less time guessing.</em></h1>
          <p className="hero-description">Find a promising place and time, see the tides, and check the rules before you go. Built for fishing around Aotearoa New Zealand.</p>
          <div className="hero-actions">
            <a className="button button-primary" href="#how-it-works">See how it works <span aria-hidden="true">↗</span></a>
            <Link className="button button-secondary" href="/account">Manage your account</Link>
          </div>
        </div>
        <div className="trip-preview" aria-label="Trip planning steps">
          <div className="trip-preview-top">
            <span className="preview-sun" aria-hidden="true">✳</span>
            <span>THE CATCHCHECK WAY</span>
          </div>
          <h2>A clearer plan for your day out.</h2>
          <ol className="preview-steps">
            <li><span>01</span><div><strong>Choose your area</strong><small>Near you or somewhere new</small></div></li>
            <li><span>02</span><div><strong>Find your window</strong><small>Conditions that suit your trip</small></div></li>
            <li><span>03</span><div><strong>Check the rules</strong><small>Know what applies locally</small></div></li>
          </ol>
          <p className="preview-caption">Land and boat fishing, all in one place.</p>
        </div>
      </section>

      <section className="features" id="features" aria-labelledby="features-title">
        <div className="section-heading">
          <p className="kicker">Made for the whole trip</p>
          <h2 id="features-title">From the first idea to the last check.</h2>
          <p>Bring the decisions that matter into one simple flow.</p>
        </div>
        <div className="feature-grid">
          {features.map((feature) => (
            <article key={feature.number} className="feature-card">
              <div className="feature-card-top"><span>{feature.label}</span><b>{feature.number}</b></div>
              <h3>{feature.title}</h3>
              <p>{feature.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="workflow" id="how-it-works" aria-labelledby="workflow-title">
        <div>
          <p className="kicker">Start with a good question</p>
          <h2 id="workflow-title">Where and when should I fish?</h2>
          <p>Pick land or boat fishing, choose how far to travel, and set your date and hours. CatchCheck compares named areas and shows the reasons behind each suggestion, so you can make your own call.</p>
          <a className="button button-dark" href="https://github.com/csgo-adc/fishing-nz/releases">Get the Android preview <span aria-hidden="true">↗</span></a>
        </div>
        <div className="workflow-note">
          <span className="note-mark" aria-hidden="true">↗</span>
          <p>Conditions can change. Check the latest forecast and official fishing rules before heading out.</p>
        </div>
      </section>

      <footer className="site-footer">
        <p><strong>CatchCheck NZ</strong><span>Made for better days on the water.</span></p>
        <div><Link href="/account">Account</Link><Link href="/admin">Admin</Link><a href="https://github.com/csgo-adc/fishing-nz/releases">Android releases</a></div>
      </footer>
    </main>
  );
}
