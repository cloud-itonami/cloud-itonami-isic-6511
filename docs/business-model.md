# Business Model: Life insurance

## Classification

- Repository: `cloud-itonami-6511`
- ISIC Rev.5: `6511`
- Activity: individual and group life insurance -- underwriting, premium collection, and death/maturity benefit claims
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- mutual and cooperative life insurers
- community and faith-based mutual-aid societies running a life-benefit fund
- employers administering a group life plan
- licensed independent life insurers who want to self-host instead of buying a closed core-insurance SaaS

## Offer

- policy intake and underwriting proposal (the advisor drafts, a licensed human underwriter binds)
- premium billing and collection
- death and maturity benefit claim intake and evaluation
- beneficiary and policy-lapse tracking
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per policy-in-force
- support: monthly retainer with SLA
- migration: import from an incumbent core-insurance system or spreadsheets
- underwriting-API access fee

## Trust Controls

- no policy is bound and no benefit is paid without human sign-off
- fabricated underwriting rationale or medical data forces a hold, not an override
- personal health data stays outside Git
- every bind, claim and payout path is auditable
- emergency manual override paths remain outside LLM control
