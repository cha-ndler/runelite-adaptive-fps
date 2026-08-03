package com.chandler.adaptivefps;

public enum HeadroomMode
{
	AUTOMATIC,
	FIXED;

	@Override
	public String toString()
	{
		return this == AUTOMATIC ? "Automatic" : "Fixed";
	}
}
